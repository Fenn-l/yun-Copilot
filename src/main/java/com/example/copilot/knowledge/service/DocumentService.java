package com.example.copilot.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.knowledge.dto.ChunkVO;
import com.example.copilot.knowledge.dto.DocumentVO;
import com.example.copilot.knowledge.entity.KbChunk;
import com.example.copilot.knowledge.entity.KbDocument;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import com.example.copilot.knowledge.mapper.KbChunkMapper;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.parser.DocumentParserRegistry;
import com.example.copilot.knowledge.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档（{@code kb_document}）业务门面：负责上传、分页/详情查询、切片查看、重处理、删除与全量索引重建。
 *
 * <p>在 RAG 链路中的位置：它是「写链路」的入口，处在「文件上传」与「异步解析/向量化」之间。
 * 上传接口被刻意做得很轻——只做本地落盘 + 插入 kb_document 记录 + 提交异步任务便立即返回，
 * 解析、切片、Embedding 这些耗时工作全部交给 {@link DocumentProcessService} 异步完成，
 * 从而让 HTTP 请求快速返回，用户通过轮询列表/详情观察状态变化。
 *
 * <p>关键设计与约束：
 * <ul>
 *   <li>文件类型与大小白名单：扩展名先经 {@code parserRegistry.normalizeExtension} 归一化再判定是否支持
 *       （PDF / DOC / DOCX / TXT / MD），体积上限取自配置 {@code rag.max-upload-mb}。</li>
 *   <li>落盘与入库的一致性：先落盘再 insert，insert 抛异常时反向删除已落盘文件做补偿，避免产生孤儿文件。</li>
 *   <li>状态机 UPLOADED → PROCESSING → COMPLETED / FAILED；{@code process_version} 是幂等版本号，
 *       reprocess 时递增，使「旧任务的写入」在落库前失效（见 {@link DocumentIndexWriter}）。</li>
 *   <li>删除文档：事务内删除切片、删除记录，并删除本地落盘文件；正在跑的异步任务因文档已不存在会
 *       抛 {@link DocumentIndexWriter.StaleIndexException} 被安全丢弃。</li>
 *   <li>切换 Embedding 模型后必须调用 {@link #reprocessAll()} 重建全部索引，否则旧向量与当前模型不一致，
 *       检索时会被 {@code embedding_model} 条件整体过滤掉。</li>
 *   <li>所有查询都基于 {@link KnowledgeBaseService#visible} 得到的可见知识库集合做数据级过滤。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** 文档状态的合法取值集合，用于分页查询时校验 status 参数：非法值直接忽略而非报错。 */
    private static final Set<String> STATUS = Set.of("UPLOADED", "PROCESSING", "COMPLETED", "FAILED");

    // 以下依赖由 Lombok @RequiredArgsConstructor 生成构造器注入。
    /** 文档表 Mapper：记录的增删改查与带版本条件的抢占式更新。 */
    private final KbDocumentMapper documentMapper;
    /** 切片表 Mapper：按文档删除切片、查询切片列表。 */
    private final KbChunkMapper chunkMapper;
    /** 知识库服务：提供可见集合与可读/可写鉴权。 */
    private final KnowledgeBaseService knowledgeBaseService;
    /** 解析器注册中心：扩展名归一化与类型支持判定。 */
    private final DocumentParserRegistry parserRegistry;
    /** 异步处理服务：上传/重处理后由其提交后台任务。 */
    private final DocumentProcessService processService;
    /** 本地文件存储：落盘、读取、删除文档文件。 */
    private final FileStorage fileStorage;
    /** 应用配置：文件大小上限、切片大小/重叠、TopK、阈值等 RAG 参数。 */
    private final AppProperties properties;

    /**
     * 上传文档：校验 → 落盘 → 插入记录 → 提交异步任务 → 立即返回，是本类最核心的写链路入口。
     *
     * <p>执行顺序与规则：
     * <ol>
     *   <li>先 {@code requireWritable} 校验当前用户对目标知识库有写权限（数据级鉴权前置）。</li>
     *   <li>空文件拒绝；体积超过 {@code maxUploadMb} 拒绝；扩展名归一化后不在支持列表内拒绝。</li>
     *   <li>{@code fileStorage.store} 落盘，随后构造 kb_document 记录：状态 UPLOADED、chunk_count=0、
     *       process_version=0，并冗余 file_type / file_size / file_url。</li>
     *   <li>insert 失败时删除已落盘文件做补偿，避免磁盘残留。</li>
     *   <li>提交 {@code processAsync(documentId, 0)} 后立即返回 VO。</li>
     * </ol>
     *
     * <p>状态语义：返回时文档通常仍是 UPLOADED（异步线程尚未处理完），前端应轮询列表/详情等待
     * COMPLETED 或 FAILED；接口本身不阻塞。
     *
     * @param knowledgeBaseId 目标知识库 ID
     * @param file            上传的文件
     * @return 新建文档的 VO（含初始状态与处理信息）
     * @throws BusinessException 无写权限 / 空文件 / 超限 / 不支持的类型 / 知识库不可写时抛出
     */
    public DocumentVO upload(Long knowledgeBaseId, MultipartFile file) {
        UserPrincipal principal = CurrentUser.get();
        KnowledgeBase kb = knowledgeBaseService.requireWritable(knowledgeBaseId, principal);
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的文件");
        }
        // 大小白名单：配置以 MB 为单位，换算成字节后比较；超过上限直接拒绝，避免大文件拖垮解析与 Embedding。
        long maxBytes = properties.getRag().getMaxUploadMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw BusinessException.badRequest("文件大小超过限制（" + properties.getRag().getMaxUploadMb() + "MB）");
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "document";
        // 扩展名归一化（去点、转小写等）后再判定，防止大小写或带点写法绕过白名单。
        String extension = parserRegistry.normalizeExtension(originalName);
        if (!parserRegistry.isSupported(extension)) {
            throw BusinessException.badRequest("暂不支持该文件类型：" + extension
                    + "，支持 PDF / DOC / DOCX / TXT / MD");
        }
        String storedPath = fileStorage.store(file, kb.getId());
        KbDocument document = new KbDocument();
        document.setKnowledgeBaseId(kb.getId());
        document.setDocumentName(originalName);
        document.setFileUrl(storedPath);
        document.setFileType(extension);
        document.setFileSize(file.getSize());
        document.setStatus("UPLOADED");
        document.setChunkCount(0);
        document.setProcessVersion(0);
        try {
            documentMapper.insert(document);
        } catch (RuntimeException e) {
            // 补偿：入库失败时回滚已落盘的文件，避免出现没有记录却占着磁盘的孤儿文件。
            fileStorage.delete(storedPath);
            throw e;
        }
        // 只提交异步任务，不等待处理结果，实现「上传快速返回」。
        processService.processAsync(document.getId(), document.getProcessVersion());
        return toVO(document, kb);
    }

    /**
     * 分页查询文档，结果受「当前用户可见知识库」约束（数据级权限过滤）。
     *
     * <p>规则：先取可见知识库 ID 列表，为空直接返回空页；随后查询条件强制带上
     * {@code knowledge_base_id IN visibleIds}。当调用方显式传 knowledgeBaseId 时，还要再校验它确实在可见集合
     * 内，否则抛 403——防止通过传一个无权知识库 ID 越权探测其文档。
     *
     * <p>参数语义：status 仅当属于 {@link #STATUS} 集合时才作为等值条件（非法值忽略而非报错）；
     * keyword 按文档名做模糊匹配；结果按 id 倒序，并用可见知识库映射补齐 VO 里的知识库名称。
     *
     * @param page            页码，从 1 开始
     * @param size            每页条数
     * @param knowledgeBaseId 可选，按知识库收窄（必须属于可见集合）
     * @param status          可选，按状态过滤（仅合法状态生效）
     * @param keyword         可选，按文档名模糊匹配
     * @return 文档 VO 分页结果
     * @throws BusinessException 显式指定的知识库不可见时抛 403
     */
    public PageResult<DocumentVO> page(long page, long size, Long knowledgeBaseId, String status, String keyword) {
        UserPrincipal principal = CurrentUser.get();
        List<Long> visibleIds = knowledgeBaseService.visible(principal).stream().map(KnowledgeBase::getId).toList();
        if (visibleIds.isEmpty()) {
            return new PageResult<>(List.of(), 0, page, size);
        }
        var wrapper = Wrappers.<KbDocument>lambdaQuery()
                .in(KbDocument::getKnowledgeBaseId, visibleIds)
                .orderByDesc(KbDocument::getId);
        if (knowledgeBaseId != null) {
            // 二次校验：显式指定的知识库必须属于可见集合，杜绝越权传 ID。
            if (!visibleIds.contains(knowledgeBaseId)) {
                throw BusinessException.forbidden("无权访问该知识库");
            }
            wrapper.eq(KbDocument::getKnowledgeBaseId, knowledgeBaseId);
        }
        if (StringUtils.hasText(status) && STATUS.contains(status)) {
            wrapper.eq(KbDocument::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(KbDocument::getDocumentName, keyword);
        }
        Page<KbDocument> result = documentMapper.selectPage(new Page<>(page, size), wrapper);
        Map<Long, KnowledgeBase> kbMap = knowledgeBaseService.visible(principal).stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, Function.identity()));
        List<DocumentVO> list = result.getRecords().stream()
                .map(doc -> toVO(doc, kbMap.get(doc.getKnowledgeBaseId())))
                .toList();
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 查询单个文档详情，先做文档级可读校验，再补齐知识库级可读校验，最后转 VO。
     *
     * <p>VO 中携带 status / errorMessage / chunkCount / embeddingModel / embeddingDim 等处理态信息，
     * 供前端展示解析进度、失败原因与当前使用的 Embedding 模型。
     *
     * @param id 文档主键
     * @return 文档 VO
     */
    public DocumentVO detail(Long id) {
        UserPrincipal principal = CurrentUser.get();
        KbDocument document = requireReadable(id, principal);
        KnowledgeBase kb = knowledgeBaseService.requireReadable(document.getKnowledgeBaseId(), principal);
        return toVO(document, kb);
    }

    /**
     * 查看某文档的切片列表（调试/审计用），按 chunk_index 升序返回，且不含向量 BLOB。
     *
     * <p>规则：先做可读鉴权；limit 缺省 100、上限 500，用 {@code last("LIMIT n")} 兜底防止一次拉出过多文本。
     * 返回的 {@link ChunkVO} 只有正文、页码、序号、字符数，刻意不把 float32 向量吐给前端。
     *
     * @param id    文档主键
     * @param limit 返回条数上限，为空或非正时按 100 处理
     * @return 切片 VO 列表
     */
    public List<ChunkVO> chunks(Long id, Integer limit) {
        UserPrincipal principal = CurrentUser.get();
        requireReadable(id, principal);
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return chunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                        .eq(KbChunk::getDocumentId, id)
                        .orderByAsc(KbChunk::getChunkIndex)
                        .last("LIMIT " + size))
                .stream()
                .map(chunk -> ChunkVO.builder()
                        .id(chunk.getId())
                        .documentId(chunk.getDocumentId())
                        .chunkIndex(chunk.getChunkIndex())
                        .pageNumber(chunk.getPageNumber())
                        .content(chunk.getContent())
                        .charCount(chunk.getCharCount())
                        .build())
                .toList();
    }

    /**
     * 重处理单个文档：递增 {@code process_version} 并提交新的异步任务，事务内完成状态抢占。
     *
     * <p>关键规则：
     * <ul>
     *   <li>先做「可读 + 可写」鉴权；文档处于 PROCESSING 时拒绝（409），避免并发重复处理。</li>
     *   <li>{@code newVersion = 当前版本 + 1}；用【带版本条件的 update】把状态重置为 UPLOADED、版本 +1、
     *       清空 errorMessage、chunk_count 归零。条件里带上旧的 process_version，实现乐观锁式抢占。</li>
     *   <li>{@code rows == 0} 说明期间版本已被别的请求改动（并发），抛 409 让调用方刷新重试，绝不盲目覆盖。</li>
     *   <li>抢占成功后才提交 {@code processAsync(id, newVersion)}。</li>
     * </ul>
     *
     * <p>为什么递增版本号：旧异步任务仍持有旧版本号，它在 {@link DocumentIndexWriter#save} 落库前会二次校验
     * 版本，发现不匹配即抛 {@link DocumentIndexWriter.StaleIndexException} 并丢弃结果，从而保证「旧任务不能
     * 覆盖新任务的新结果」，这是重处理幂等的核心。
     *
     * @param id 文档主键
     * @return 回查到的最新文档 VO（状态应为 UPLOADED，版本已递增）
     * @throws BusinessException 处理中 / 无权限 / 状态并发变化时抛出（冲突类为 409）
     */
    @Transactional
    public DocumentVO reprocess(Long id) {
        UserPrincipal principal = CurrentUser.get();
        KbDocument document = requireReadable(id, principal);
        KnowledgeBase kb = knowledgeBaseService.requireWritable(document.getKnowledgeBaseId(), principal);
        if ("PROCESSING".equals(document.getStatus())) {
            throw BusinessException.conflict("文档正在处理中，请稍后再试");
        }
        // 版本号自增，作为本次重处理的唯一标识，用于让所有旧任务结果失效。
        int newVersion = (document.getProcessVersion() == null ? 0 : document.getProcessVersion()) + 1;
        // 带版本条件的抢占式更新：只有版本仍等于读取时的旧值才能改成功，等价于乐观锁 CAS。
        int rows = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getProcessVersion, document.getProcessVersion())
                .set(KbDocument::getStatus, "UPLOADED")
                .set(KbDocument::getProcessVersion, newVersion)
                .set(KbDocument::getErrorMessage, null)
                .set(KbDocument::getChunkCount, 0));
        if (rows == 0) {
            throw BusinessException.conflict("文档状态已变化，请刷新后重试");
        }
        processService.processAsync(id, newVersion);
        KbDocument refreshed = documentMapper.selectById(id);
        return toVO(refreshed, kb);
    }

    /**
     * 删除文档，事务内清理 DB 侧数据并删除落盘文件。
     *
     * <p>级联内容：按 document_id 删除该文档的全部切片（含向量 BLOB）→ 删除文档记录 → 删除本地文件。
     * 先删切片再删记录，避免留下没有文档归属的孤儿切片。
     *
     * <p>边界：若此时恰有异步任务在处理该文档，它最终调用 {@link DocumentIndexWriter#save} 时会因
     * 「文档已被删除」抛 {@link DocumentIndexWriter.StaleIndexException}，结果被安静丢弃，不会复活已删文档。
     *
     * @param id 文档主键
     */
    @Transactional
    public void delete(Long id) {
        UserPrincipal principal = CurrentUser.get();
        KbDocument document = requireReadable(id, principal);
        knowledgeBaseService.requireWritable(document.getKnowledgeBaseId(), principal);
        chunkMapper.delete(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocumentId, id));
        documentMapper.deleteById(id);
        fileStorage.delete(document.getFileUrl());
    }

    /**
     * 重建全部文档的向量索引，仅管理员可调用，是切换 Embedding 模型后的必跑操作。
     *
     * <p>背景：切片向量是按「当时的 Embedding 模型」生成并冗余了 embedding_model 字段；检索时
     * {@link com.example.copilot.knowledge.retrieval.VectorRetriever} 会按当前模型名过滤候选，旧模型向量会被
     * 整体排除。因此运行时切换模型后，必须对所有文档重跑解析+向量化，才能用新模型重建索引。
     *
     * <p>规则：遍历全表文档，跳过正在 PROCESSING 的（避免与在途任务打架）；对每篇用「带版本条件的 update」
     * 递增 process_version 并重置为 UPLOADED，抢占成功（rows &gt; 0）才提交异步任务并计数；并发导致抢占失败的
     * 跳过即可。返回成功触发的任务数。
     *
     * @return 实际提交重建的文档数量
     * @throws BusinessException 非管理员调用时抛 403
     */
    public int reprocessAll() {
        UserPrincipal principal = CurrentUser.get();
        if (!principal.isAdmin()) {
            throw BusinessException.forbidden("只有管理员可以重建全部向量索引");
        }
        List<KbDocument> documents = documentMapper.selectList(null);
        int triggered = 0;
        for (KbDocument document : documents) {
            if ("PROCESSING".equals(document.getStatus())) {
                continue;
            }
            int newVersion = (document.getProcessVersion() == null ? 0 : document.getProcessVersion()) + 1;
            // 与单篇 reprocess 相同的版本抢占：并发改过版本的文档会被跳过，不强行覆盖。
            int rows = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                    .eq(KbDocument::getId, document.getId())
                    .eq(KbDocument::getProcessVersion, document.getProcessVersion())
                    .set(KbDocument::getStatus, "UPLOADED")
                    .set(KbDocument::getProcessVersion, newVersion)
                    .set(KbDocument::getErrorMessage, null)
                    .set(KbDocument::getChunkCount, 0));
            if (rows > 0) {
                processService.processAsync(document.getId(), newVersion);
                triggered++;
            }
        }
        return triggered;
    }

    /**
     * 按 ID 读取文档并强制做数据级可读校验，供本类其它方法与外部复用。
     *
     * <p>边界与异常：文档不存在抛 404；随后委托 {@link KnowledgeBaseService#requireReadable} 校验其所属知识库
     * 存在、ACTIVE 且当前用户可读，失败抛 403。
     *
     * @param id        文档主键
     * @param principal 当前登录主体
     * @return 校验通过的文档实体
     */
    public KbDocument requireReadable(Long id, UserPrincipal principal) {
        KbDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw BusinessException.notFound("文档不存在");
        }
        knowledgeBaseService.requireReadable(document.getKnowledgeBaseId(), principal);
        return document;
    }

    /**
     * 把文档实体转换为对外 VO，并填入所属知识库名称与全部处理态字段。
     *
     * <p>字段语义：status / errorMessage / chunkCount 反映异步处理进度与结果，embeddingModel / embeddingDim
     * 记录该文档切片所用的模型与维度（重处理完成后才写入）。kb 为 null 时知识库名称留空，避免 NPE。
     *
     * @param document 文档实体，为 null 时返回 null
     * @param kb       所属知识库，可为 null
     * @return 文档 VO
     */
    public DocumentVO toVO(KbDocument document, KnowledgeBase kb) {
        if (document == null) {
            return null;
        }
        return DocumentVO.builder()
                .id(document.getId())
                .knowledgeBaseId(document.getKnowledgeBaseId())
                .knowledgeBaseName(kb == null ? null : kb.getName())
                .documentName(document.getDocumentName())
                .fileType(document.getFileType())
                .fileSize(document.getFileSize())
                .status(document.getStatus())
                .errorMessage(document.getErrorMessage())
                .chunkCount(document.getChunkCount())
                .embeddingModel(document.getEmbeddingModel())
                .embeddingDim(document.getEmbeddingDim())
                .createTime(document.getCreateTime())
                .updateTime(document.getUpdateTime())
                .build();
    }

    /**
     * 上传种子/预置文档（系统演示数据初始化用），绕过前端上传流程。
     *
     * <p>与 {@link #upload} 的区别：直接以字节数组 {@code storeBytes} 落盘，不做登录鉴权、不做类型/大小白名单
     * （extension 由调用方给定），初始 process_version=0，随后同样提交 {@code processAsync(id, 0)} 走标准异步链路。
     * 仅应被内部初始化逻辑调用，不暴露为 HTTP 端点。
     *
     * @param kb        目标知识库
     * @param fileName  文档名
     * @param bytes     文件内容
     * @param extension 文件类型（PDF / DOC / DOCX / TXT / MD 之一）
     */
    public void uploadSeedDocument(KnowledgeBase kb, String fileName, byte[] bytes, String extension) {
        String storedPath = fileStorage.storeBytes(bytes, fileName, kb.getId());
        KbDocument document = new KbDocument();
        document.setKnowledgeBaseId(kb.getId());
        document.setDocumentName(fileName);
        document.setFileUrl(storedPath);
        document.setFileType(extension);
        document.setFileSize((long) bytes.length);
        document.setStatus("UPLOADED");
        document.setChunkCount(0);
        document.setProcessVersion(0);
        documentMapper.insert(document);
        processService.processAsync(document.getId(), 0);
    }

    /**
     * 判断系统内是否已存在任意文档，供启动引导/演示数据初始化判断是否跳过灌入。
     *
     * @return 存在至少一条文档记录时返回 true
     */
    public boolean hasAnyDocument() {
        return documentMapper.selectCount(null) > 0;
    }
}
