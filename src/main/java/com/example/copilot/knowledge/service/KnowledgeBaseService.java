package com.example.copilot.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.copilot.knowledge.dto.KnowledgeBaseVO;
import com.example.copilot.knowledge.entity.KbChunk;
import com.example.copilot.knowledge.entity.KbDocument;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import com.example.copilot.knowledge.mapper.KbChunkMapper;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.mapper.KnowledgeBaseMapper;
import com.example.copilot.knowledge.storage.FileStorage;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库（{@code kb_knowledge_base}）的业务服务：负责可见范围计算、数据级权限鉴权，以及知识库的增删改查。
 *
 * <p>在 RAG 链路中的位置：它是向量检索的「第一道闸门」。
 * {@link com.example.copilot.knowledge.retrieval.VectorRetriever} 在打分之前会先调用
 * {@link #visible(UserPrincipal)} 取得「当前用户可见的知识库 ID 列表」，并把它下推到候选切片的 SQL 过滤条件里。
 * 也就是说，权限过滤发生在向量检索/相似度打分【之前】，而不是把全库切片都捞出来、再靠 Prompt 叮嘱模型「不要引用无权内容」。
 * 后者既浪费算力又不可靠，越权内容一旦进入上下文就可能造成数据泄露或幻觉；这里用数据级鉴权从源头杜绝。
 *
 * <p>关键设计与约束：
 * <ul>
 *   <li>三级可见范围：PUBLIC（全员可读）/ INTERNAL（客服 AGENT 与管理员可读）/ PRIVATE（仅创建人与管理员可读）。</li>
 *   <li>三级角色：管理员 ADMIN &gt; 客服 AGENT &gt; 普通用户 USER；权限判定统一收敛到
 *       {@link #canRead} / {@link #canWrite} 两个纯函数，便于复用与测试。</li>
 *   <li>可读性校验与存在性校验分离：{@link #requireReadable} / {@link #requireWritable} 在权限判定之上叠加
 *       「知识库存在且状态为 ACTIVE」的校验，并抛出统一业务异常；所有写操作入口都先过这一层。</li>
 *   <li>创建 / 更新知识库时，普通用户（非客服）只能建或保持 PRIVATE，防止其借 PUBLIC / INTERNAL 把私有资料暴露出去。</li>
 *   <li>删除知识库是一个事务内的级联清理：先删除该库全部文档的落盘文件，再按 knowledge_base_id 删除切片、文档，
 *       最后删除知识库本身，避免留下孤儿向量与磁盘垃圾。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    /** 可见范围白名单，用于在创建/更新时拒绝非法枚举值（PUBLIC / INTERNAL / PRIVATE）。 */
    private static final Set<String> VISIBILITY = Set.of("PUBLIC", "INTERNAL", "PRIVATE");

    // 以下依赖均由 Lombok @RequiredArgsConstructor 生成构造器注入，Spring 容器自动装配。
    /** 知识库表 Mapper，负责 kb_knowledge_base 的 CRUD 与查询。 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /** 文档表 Mapper，统计文档数、按知识库查询/删除文档。 */
    private final KbDocumentMapper documentMapper;
    /** 切片表 Mapper，统计切片数、级联删除切片（切片内存放向量 BLOB）。 */
    private final KbChunkMapper chunkMapper;
    /** 用户表 Mapper，用于在 VO 中填充知识库拥有者昵称。 */
    private final UserMapper userMapper;
    /** 本地文件存储，删除知识库时用于清理文档对应的磁盘文件。 */
    private final FileStorage fileStorage;

    /**
     * 计算某用户可见的全部知识库，是 RAG 检索权限过滤的数据来源。
     *
     * <p>规则：先取出所有状态为 ACTIVE 的知识库（按 id 倒序），再用 {@link #canRead} 逐条做数据级过滤。
     * 只返回 ACTIVE 库，已停用的知识库不参与检索与列表展示。
     *
     * <p>设计意图：返回的是「可见知识库实体列表」，调用方（VectorRetriever、DocumentService.page）会进一步
     * 抽取其 ID，作为 SQL 的 IN 条件。把权限前置到这里，保证越权切片根本不会进入候选集。
     *
     * @param principal 当前登录主体，携带角色与用户 ID
     * @return 当前用户可读的知识库列表（不可变视图）；无可见知识库时返回空列表
     */
    public List<KnowledgeBase> visible(UserPrincipal principal) {
        List<KnowledgeBase> all = knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getStatus, "ACTIVE")
                .orderByDesc(KnowledgeBase::getId));
        return all.stream().filter(kb -> canRead(kb, principal)).toList();
    }

    /**
     * 判断单个知识库对某主体是否可读，是三级权限判定的核心。
     *
     * <p>规则（短路顺序即优先级）：管理员一律放行；知识库创建人（ownerId 与主体 ID 相等）一律放行；
     * 否则按可见范围判定——PUBLIC 全员可读，INTERNAL 仅客服（AGENT）可读，其余一律拒绝。
     *
     * <p>注意 PRIVATE 的可读主体是「创建人 + 管理员」，这两类已由前面两条覆盖；switch 的 default 分支
     * 作为兜底，把 PRIVATE 的非创建人、以及任何未知可见范围都判为不可读（最小权限原则）。
     *
     * @param kb        待判定的知识库
     * @param principal 当前登录主体
     * @return 是否可读
     */
    public boolean canRead(KnowledgeBase kb, UserPrincipal principal) {
        if (principal.isAdmin()) {
            return true;
        }
        if (Objects.equals(kb.getOwnerId(), principal.getId())) {
            return true;
        }
        // 走到这里说明主体既非管理员也非创建人：PUBLIC 全员放行；
        // INTERNAL 只对客服开放；PRIVATE 及未知值一律拒绝（default）。
        return switch (kb.getVisibility()) {
            case "PUBLIC" -> true;
            case "INTERNAL" -> principal.isAgent();
            default -> false;
        };
    }

    /**
     * 判断单个知识库对某主体是否可写（上传文档、重处理、删除文档、改知识库等写操作的准入门槛）。
     *
     * <p>规则：管理员或创建人放行；客服（AGENT）可写非 PRIVATE 的知识库（即 PUBLIC / INTERNAL，便于客服维护共享库）；
     * 普通用户即便面对 PUBLIC 库也无写权限——它只能管理自己创建的 PRIVATE 库。
     *
     * <p>注意客服被显式排除在 PRIVATE 库之外，避免客服改动他人私有知识库；这与可读判定中
     * 「PRIVATE 仅创建人 + 管理员」保持一致的收紧口径。
     *
     * @param kb        待判定的知识库
     * @param principal 当前登录主体
     * @return 是否可写
     */
    public boolean canWrite(KnowledgeBase kb, UserPrincipal principal) {
        if (principal.isAdmin() || Objects.equals(kb.getOwnerId(), principal.getId())) {
            return true;
        }
        return principal.isAgent() && !"PRIVATE".equals(kb.getVisibility());
    }

    /**
     * 读取知识库实体并强制做「存在 + 状态 + 可读」三重校验，供各 Service / Controller 复用。
     *
     * <p>边界与异常：知识库不存在或状态非 ACTIVE 时抛 404（notFound，不暴露库是否存在的信息）；
     * 存在但 {@link #canRead} 为 false 时抛 403（forbidden）。
     *
     * @param id        知识库主键
     * @param principal 当前登录主体
     * @return 校验通过的知识库实体
     */
    public KnowledgeBase requireReadable(Long id, UserPrincipal principal) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || !"ACTIVE".equals(kb.getStatus())) {
            throw BusinessException.notFound("知识库不存在");
        }
        if (!canRead(kb, principal)) {
            throw BusinessException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    /**
     * 在 {@link #requireReadable} 之上再叠加可写校验，是所有写操作（上传、删除、更新、重处理）的统一入口。
     *
     * <p>边界与异常：先保证库存在且可读，再要求 {@link #canWrite} 为 true，否则抛 403（forbidden）。
     *
     * @param id        知识库主键
     * @param principal 当前登录主体
     * @return 校验通过且可写的知识库实体
     */
    public KnowledgeBase requireWritable(Long id, UserPrincipal principal) {
        KnowledgeBase kb = requireReadable(id, principal);
        if (!canWrite(kb, principal)) {
            throw BusinessException.forbidden("无权管理该知识库");
        }
        return kb;
    }

    /**
     * 列出当前登录用户可见的知识库 VO，是 Controller 列表端点的服务入口。
     *
     * <p>主体从线程上下文 {@code CurrentUser.get()} 取得（由登录拦截/JWT 过滤器写入），无需从参数传入。
     * 每个 VO 还会附带文档数、切片数、拥有者昵称等聚合信息，见 {@link #toVO}。
     *
     * @return 可见知识库的 VO 列表
     */
    public List<KnowledgeBaseVO> listVisible() {
        UserPrincipal principal = CurrentUser.get();
        return visible(principal).stream().map(this::toVO).toList();
    }

    /**
     * 创建知识库，事务内插入一条 ACTIVE 记录。
     *
     * <p>规则：可见范围缺省为 PRIVATE、统一转大写后按 {@link #VISIBILITY} 白名单校验（非法值抛 400）；
     * 普通用户（非客服）只能创建 PRIVATE，试图建 PUBLIC / INTERNAL 时抛 403——防止普通用户扩大资料可见面；
     * ownerId 绑定当前用户，status 置 ACTIVE。
     *
     * <p>边界：名称去除首尾空格；名称非空等基础校验由 Controller 的 {@code @Valid} 在请求体层完成。
     *
     * @param request 创建请求（name / description / visibility）
     * @return 新建知识库的 VO
     */
    @Transactional
    public KnowledgeBaseVO create(CreateKnowledgeBaseRequest request) {
        UserPrincipal principal = CurrentUser.get();
        String visibility = StringUtils.hasText(request.getVisibility())
                ? request.getVisibility().toUpperCase() : "PRIVATE";
        if (!VISIBILITY.contains(visibility)) {
            throw BusinessException.badRequest("非法可见范围");
        }
        // 普通用户不允许创建非私有库：这是「非客服只能建 PRIVATE」的业务约束。
        if (!principal.isAgent() && !"PRIVATE".equals(visibility)) {
            throw BusinessException.forbidden("普通用户创建的知识库必须是私有可见");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName().trim());
        kb.setDescription(request.getDescription());
        // 拥有者绑定当前登录用户，PRIVATE 库的可读/可写权限据此判定。
        kb.setOwnerId(principal.getId());
        kb.setVisibility(visibility);
        kb.setStatus("ACTIVE");
        knowledgeBaseMapper.insert(kb);
        return toVO(kb);
    }

    /**
     * 查询单个知识库详情，先做可读校验再转 VO。
     *
     * @param id 知识库主键
     * @return 知识库 VO（含文档数、切片数、拥有者昵称）
     */
    public KnowledgeBaseVO detail(Long id) {
        UserPrincipal principal = CurrentUser.get();
        return toVO(requireReadable(id, principal));
    }

    /**
     * 更新知识库的名称、描述与可见范围，事务内执行。
     *
     * <p>规则：可见范围缺省沿用原值、统一转大写并按白名单校验；与创建对称，普通用户必须保持 PRIVATE
     * （不能把自己建的库提权为 PUBLIC / INTERNAL），否则抛 403。写操作前先经 {@link #requireWritable} 鉴权。
     *
     * <p>实现细节：更新后回查一次最新实体再转 VO，保证返回的是库里的真实值（含更新时间等由 DB 维护的字段）。
     *
     * @param id      知识库主键
     * @param request 更新请求
     * @return 更新后的知识库 VO
     */
    @Transactional
    public KnowledgeBaseVO update(Long id, CreateKnowledgeBaseRequest request) {
        UserPrincipal principal = CurrentUser.get();
        KnowledgeBase kb = requireWritable(id, principal);
        String visibility = StringUtils.hasText(request.getVisibility())
                ? request.getVisibility().toUpperCase() : kb.getVisibility();
        if (!VISIBILITY.contains(visibility)) {
            throw BusinessException.badRequest("非法可见范围");
        }
        // 与创建保持一致：普通用户的知识库必须维持私有可见，防止提权。
        if (!principal.isAgent() && !"PRIVATE".equals(visibility)) {
            throw BusinessException.forbidden("普通用户的知识库必须保持私有可见");
        }
        kb.setName(request.getName().trim());
        kb.setDescription(request.getDescription());
        kb.setVisibility(visibility);
        knowledgeBaseMapper.updateById(kb);
        return toVO(knowledgeBaseMapper.selectById(id));
    }

    /**
     * 删除知识库并级联清理其全部下游数据，事务内完成 DB 侧的一致性。
     *
     * <p>权限比 canWrite 更严：仅知识库创建人或管理员可删；客服即便对共享库有写权限，也不能删除他人创建的库。
     *
     * <p>级联顺序（为什么都要删）：向量以 float32 BLOB 存在 kb_chunk，原始文件落在本地磁盘，二者都不会随
     * 知识库记录自动消失。因此先逐个删除文档对应的磁盘文件，再按 knowledge_base_id 删除切片、删除文档，
     * 最后删除知识库本身，避免残留「幽灵向量」和磁盘垃圾。
     *
     * <p>边界：文件删除不受数据库事务回滚保护（属最终一致，删库后若事务回滚会留下磁盘文件，但不会产生可被
     * 检索到的 DB 记录）；DB 侧的切片/文档/知识库删除在同一事务内，要么全成要么全滚。
     *
     * @param id 知识库主键
     */
    @Transactional
    public void delete(Long id) {
        UserPrincipal principal = CurrentUser.get();
        KnowledgeBase kb = requireReadable(id, principal);
        // 删除权限收紧到「创建人或管理员」，比 canWrite 更严格。
        if (!principal.isAdmin() && !Objects.equals(kb.getOwnerId(), principal.getId())) {
            throw BusinessException.forbidden("只有知识库创建人或管理员可以删除");
        }
        List<KbDocument> documents = documentMapper.selectList(Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getKnowledgeBaseId, id));
        // 先清理磁盘文件：文档记录删除后文件路径即丢失，必须先取出来再删。
        for (KbDocument document : documents) {
            fileStorage.delete(document.getFileUrl());
        }
        // 级联删除顺序：切片（含向量）→ 文档 → 知识库，均由 knowledge_base_id 定位。
        chunkMapper.delete(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getKnowledgeBaseId, id));
        documentMapper.delete(Wrappers.<KbDocument>lambdaQuery().eq(KbDocument::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    /**
     * 把知识库实体聚合为 VO：附带拥有者昵称、文档数、切片数等展示字段。
     *
     * <p>实现说明：每次转换都会额外查询一次拥有者，并分别 count 文档数与切片数；列表接口对每个可见库都
     * 会触发这几条查询（当前实现以简洁优先，未做批量聚合）。count 口径与可见性无关，统计的是该库全量数据。
     *
     * @param kb 知识库实体
     * @return 组装好的 KnowledgeBaseVO
     */
    public KnowledgeBaseVO toVO(KnowledgeBase kb) {
        User owner = userMapper.selectById(kb.getOwnerId());
        long documentCount = documentMapper.selectCount(Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getKnowledgeBaseId, kb.getId()));
        long chunkCount = chunkMapper.selectCount(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getKnowledgeBaseId, kb.getId()));
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .ownerId(kb.getOwnerId())
                .ownerName(owner == null ? null : owner.getNickname())
                .visibility(kb.getVisibility())
                .status(kb.getStatus())
                .documentCount(documentCount)
                .chunkCount(chunkCount)
                .createTime(kb.getCreateTime())
                .updateTime(kb.getUpdateTime())
                .build();
    }
}
