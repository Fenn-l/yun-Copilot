package com.example.copilot.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.copilot.ai.dto.CitationVO;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.conversation.dto.ConversationVO;
import com.example.copilot.conversation.dto.MessageVO;
import com.example.copilot.conversation.entity.Conversation;
import com.example.copilot.conversation.entity.Message;
import com.example.copilot.conversation.mapper.ConversationMapper;
import com.example.copilot.conversation.mapper.MessageMapper;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话域核心业务服务，负责会话生命周期、消息落库与视图组装，是 Controller 与持久化层之间的业务边界。
 *
 * <p>业务链路位置：承接 /api/conversations 的接口调用，同时被 AI 问答 / Copilot / 工单域复用——
 * appendMessage 供问答链路写入 USER / ASSISTANT 消息，recentMessages 供问答链路拼装上下文窗口，
 * handoffRequired 标记供工单域在转人工时以 conversation_id 关联并生成工单。
 *
 * <ul>
 *   <li>数据级权限：requireAccessible 统一校验——会话不存在抛 404；普通用户（非 AGENT）只能访问
 *       userId 等于自身的会话，AGENT / ADMIN 可访问工作区会话。所有单会话操作都先过这道校验。</li>
 *   <li>会话状态：create 初始 ACTIVE，close 置 CLOSED；CLOSED 会话在问答链路侧禁止继续提问。</li>
 *   <li>上下文窗口：recentMessages 按 id 倒序 + LIMIT N 取最近 N 条再反转为时间正序；
 *       由于当前提问通常已由 appendMessage 先落库，调用方需自行剔除这条最新用户消息以避免重复注入。</li>
 *   <li>标题：首次提问调用 updateTitleIfDefault，把默认的「新的对话」改写为问题前 24 字符摘要。</li>
 *   <li>转人工：AI 知识不足或故障时置 handoffRequired，工单域取最近 8 条消息做摘要并建单，
 *       通过 conversation_id 与会话关联；本服务不直接建单，只提供标记与最近消息。</li>
 *   <li>token：tokenCount 由问答链路估算后传入 appendMessage，本服务仅持久化，不做估算。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /**
     * 会话业务编号的时间戳格式（精确到秒），与 4 位随机数拼接生成 conversationNo：C + 时间戳 + 随机数。
     */
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 会话表 Mapper，负责 cs_conversation 的插入、分页、按 id 查询与状态/标题/活跃时间的局部更新。
     */
    private final ConversationMapper conversationMapper;
    /**
     * 消息表 Mapper，负责 cs_message 写入，以及按会话 + id 排序取最近消息、取最后一条消息预览。
     */
    private final MessageMapper messageMapper;
    /**
     * 用户表 Mapper，供 toVO 关联查询创建人昵称/用户名，补齐 ConversationVO.userName。
     */
    private final UserMapper userMapper;
    /**
     * Jackson 对象映射器，用于 citations 在列表与 JSON 字符串之间互转（appendMessage 序列化、toVO 反序列化）。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建会话并落库。生成会话号（C + yyyyMMddHHmmss + 4 位随机数），userId 取当前登录用户，
     * title 为空白时回退默认「新的对话」，初始状态置 ACTIVE。事务内插入后返回 VO。
     * 边界：title 会先 trim；默认标题是后续 updateTitleIfDefault 判定是否需要自动改写的依据。
     */
    @Transactional
    public ConversationVO create(String title) {
        Conversation conversation = new Conversation();
        conversation.setConversationNo("C" + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        conversation.setUserId(CurrentUser.id());
        conversation.setTitle(StringUtils.hasText(title) ? title.trim() : "新的对话");
        conversation.setStatus("ACTIVE");
        conversationMapper.insert(conversation);
        return toVO(conversation);
    }

    /**
     * 会话分页。按 updateTime 倒序排列（最近活跃在前）。
     * 数据级权限：取 CurrentUser，非 AGENT（即普通 USER）时叠加 userId 等值条件只看自己的会话；
     * AGENT / ADMIN 不加该条件，查看工作区全部会话。返回 PageResult（记录 / 总数 / 当前页 / 每页大小）。
     */
    public PageResult<ConversationVO> page(long page, long size) {
        UserPrincipal principal = CurrentUser.get();
        var wrapper = Wrappers.<Conversation>lambdaQuery().orderByDesc(Conversation::getUpdateTime);
        // 数据级权限：普通用户强制按 userId 过滤，AGENT / ADMIN 放行查看全量工作区会话
        if (!principal.isAgent()) {
            wrapper.eq(Conversation::getUserId, principal.getId());
        }
        Page<Conversation> result = conversationMapper.selectPage(new Page<>(page, size), wrapper);
        List<ConversationVO> list = result.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 单会话访问的统一数据级权限入口，被 detail / messages / close 复用。
     * 按 id 查询，不存在抛 404（BusinessException.notFound）；
     * 普通用户（非 AGENT）且 userId 不等于当前登录用户时抛 403（forbidden）。
     * AGENT / ADMIN 通过校验，可查看工作区会话。校验通过返回会话实体。
     */
    public Conversation requireAccessible(Long id) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw BusinessException.notFound("会话不存在");
        }
        UserPrincipal principal = CurrentUser.get();
        if (!principal.isAgent() && !Objects.equals(conversation.getUserId(), principal.getId())) {
            throw BusinessException.forbidden("无权访问该会话");
        }
        return conversation;
    }

    /**
     * 会话详情。先经 requireAccessible 校验归属，再委托 toVO 组装视图（含 userName、lastMessage 预览）。
     * 权限不符时不返回数据而是抛异常，避免越权读取他人会话。
     */
    public ConversationVO detail(Long id) {
        return toVO(requireAccessible(id));
    }

    /**
     * 查询会话消息列表。先 requireAccessible 校验权限，再按 conversation_id 过滤、按 id 升序返回（时间正序）。
     * limit 为空或非正数时取全量；否则用 wrapper.last 追加 LIMIT min(limit, 500)，防止一次拉取过多消息撑爆响应。
     * 每条消息经 toVO 把 citations JSON 还原为列表后再返回。
     */
    public List<MessageVO> messages(Long id, Integer limit) {
        requireAccessible(id);
        var wrapper = Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, id)
                .orderByAsc(Message::getId);
        // 仅当传入正数 limit 才限量；数值经 Math.min 收敛后拼接，避免 SQL 片段被注入超范围取数
        if (limit != null && limit > 0) {
            // 上限 500 条：既保护接口响应体，也约束 AI 上下文窗口的取数规模
            wrapper.last("LIMIT " + Math.min(limit, 500));
        }
        return messageMapper.selectList(wrapper).stream().map(this::toVO).toList();
    }

    /**
     * 结束会话。先 requireAccessible 校验可访问性，再叠加更严格的写权限：
     * 非 ADMIN 且非会话创建人时抛 403（即 AGENT 也不得结束他人会话）。
     * 事务内把 status 置为 CLOSED 并 updateById；CLOSED 后问答链路禁止继续提问，前端应关闭输入入口。
     */
    @Transactional
    public ConversationVO close(Long id) {
        Conversation conversation = requireAccessible(id);
        UserPrincipal principal = CurrentUser.get();
        if (!principal.isAdmin() && !Objects.equals(conversation.getUserId(), principal.getId())) {
            throw BusinessException.forbidden("只有会话创建人可以结束会话");
        }
        conversation.setStatus("CLOSED");
        conversationMapper.updateById(conversation);
        return toVO(conversation);
    }

    /**
     * 追加一条消息（AI 问答 / 客服回复的统一落库入口），事务内完成消息插入与会话活跃时间刷新。
     * role 取 USER 或 ASSISTANT；messageType 固定 TEXT；tokenCount 由调用方估算后传入，本方法不计算。
     * citations 非空时用 ObjectMapper 序列化为 JSON 字符串写入（仅 ASSISTANT 命中知识库时有值），
     * 序列化失败仅告警不阻断落库；handoffRequired / handoffReason 记录是否需要转人工及原因。
     * 插入后构造仅含 id 与 updateTime 的 Conversation 做局部更新，刷新列表排序用的活跃时间。
     * 边界：不校验会话状态，CLOSED 会话的禁问由上游问答链路保证。
     */
    @Transactional
    public Message appendMessage(Long conversationId, String role, String content, int tokenCount,
                                 List<CitationVO> citations, boolean handoffRequired, String handoffReason) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType("TEXT");
        message.setTokenCount(tokenCount);
        message.setHandoffRequired(handoffRequired);
        message.setHandoffReason(handoffReason);
        if (citations != null) {
            try {
                // 引用列表序列化为 JSON 文本存入 citations 列，前端渲染时再由 toVO 反序列化还原
                message.setCitations(objectMapper.writeValueAsString(citations));
            } catch (Exception e) {
                log.warn("序列化引用失败", e);
            }
        }
        messageMapper.insert(message);
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        // 只回写 id + updateTime，避免覆盖标题/状态等其他列；用于会话列表按最近活跃倒序
        conversation.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        return message;
    }

    /**
     * 首次提问时自动生成会话标题。仅当当前标题是默认值「新的对话」或为空白时才改写，避免覆盖用户自定义标题。
     * 取问题前 24 字符作为标题（超长追加省略号），事务内只回写 id + title。
     * 边界：会话不存在直接返回（不抛异常）；question 本身的空值由调用方保证。
     */
    @Transactional
    public void updateTitleIfDefault(Long conversationId, String question) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return;
        }
        // 仅默认标题或空标题才自动改写，保护用户已手动命名的会话
        if ("新的对话".equals(conversation.getTitle()) || !StringUtils.hasText(conversation.getTitle())) {
            // 标题截断到 24 字符，超长补省略号，防止长问题撑爆列表展示
            String title = question.length() > 24 ? question.substring(0, 24) + "..." : question;
            Conversation update = new Conversation();
            update.setId(conversationId);
            update.setTitle(title);
            conversationMapper.updateById(update);
        }
    }

    /**
     * 取「最近 N 条历史消息」作为 AI 问答的上下文窗口。
     * 按 conversation_id 过滤、按 id 倒序 + LIMIT N 取出最近 N 条，再 Collections.reverse 反转为时间正序，
     * 保证喂给大模型时对话顺序正确（依赖 (conversation_id, id) 联合索引，避免全表排序）。
     * limit 用 Math.max(limit, 1) 兜底至少 1 条。
     * 边界：本方法不剔除当前提问——若调用方已先经 appendMessage 落库当前用户消息，需自行从结果中排除
     * 这条最新 USER 消息（去重），避免把当前问题重复拼进上下文；本方法也不校验会话状态。
     */
    public List<Message> recentMessages(Long conversationId, int limit) {
        // 倒序 + LIMIT 只扫描索引尾部，等价于「取最后 N 条」，比全量查询再截取更高效
        List<Message> messages = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + Math.max(limit, 1)));
        // 上面按 id 倒序取的是「最新在前」，此处反转为时间正序，才能按真实对话顺序拼接 prompt
        java.util.Collections.reverse(messages);
        return messages;
    }

    /**
     * 会话实体转视图。在实体字段外补齐两处展示数据：
     * userName 由 userMapper 关联用户表取昵称（为空回退用户名）；
     * lastMessage 取该会话最后一条消息（倒序 LIMIT 1）正文，超过 60 字符截断加省略号，无消息则为 null。
     * 用 Builder 构造 VO，避免把实体直接暴露给接口。
     */
    public ConversationVO toVO(Conversation conversation) {
        User user = userMapper.selectById(conversation.getUserId());
        // 倒序取该会话最新一条消息作为列表预览，走 (conversation_id, id) 索引
        Message last = messageMapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversation.getId())
                .orderByDesc(Message::getId)
                .last("LIMIT 1"));
        String preview = last == null ? null : last.getContent();
        // 列表预览只保留前 60 字符，超长补省略号，避免长回复撑破列表布局
        if (preview != null && preview.length() > 60) {
            preview = preview.substring(0, 60) + "...";
        }
        return ConversationVO.builder()
                .id(conversation.getId())
                .conversationNo(conversation.getConversationNo())
                .userId(conversation.getUserId())
                .userName(user == null ? null : (StringUtils.hasText(user.getNickname())
                        ? user.getNickname() : user.getUsername()))
                .title(conversation.getTitle())
                .status(conversation.getStatus())
                .lastMessage(preview)
                .createTime(conversation.getCreateTime())
                .updateTime(conversation.getUpdateTime())
                .build();
    }

    /**
     * 消息实体转视图。核心是 citations 的反序列化：实体存的是 JSON 字符串，
     * 用 ObjectMapper 配合 TypeReference 还原为列表供前端渲染引用；无内容或解析异常时降级为空列表，
     * 保证单条脏引用不会让整个消息列表接口失败。handoffRequired 以 Boolean.TRUE.equals 做 null 安全还原。
     */
    public MessageVO toVO(Message message) {
        List<CitationVO> citations = List.of();
        if (StringUtils.hasText(message.getCitations())) {
            try {
                // 把 citations JSON 反序列化为引用 VO 列表；解析失败由下方 catch 降级为空列表，防止脏数据阻断接口
                citations = objectMapper.readValue(message.getCitations(), new TypeReference<List<CitationVO>>() {
                });
            } catch (Exception e) {
                log.warn("解析消息引用失败 messageId={}", message.getId());
            }
        }
        return MessageVO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .role(message.getRole())
                .content(message.getContent())
                .messageType(message.getMessageType())
                .tokenCount(message.getTokenCount())
                .citations(citations)
                .handoffRequired(Boolean.TRUE.equals(message.getHandoffRequired()))
                .handoffReason(message.getHandoffReason())
                .createTime(message.getCreateTime())
                .build();
    }
}
