package com.example.copilot.copilot.service;

import com.example.copilot.ai.dto.CitationVO;
import com.example.copilot.ai.entity.AiSetting;
import com.example.copilot.ai.gateway.ChatTurn;
import com.example.copilot.ai.gateway.LlmChatResult;
import com.example.copilot.ai.gateway.LlmGateway;
import com.example.copilot.ai.service.AiConfigService;
import com.example.copilot.ai.service.AiRunService;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.conversation.dto.MessageVO;
import com.example.copilot.conversation.service.ConversationService;
import com.example.copilot.copilot.dto.CopilotResultVO;
import com.example.copilot.knowledge.retrieval.RetrievalHit;
import com.example.copilot.knowledge.retrieval.VectorRetriever;
import com.example.copilot.ticket.dto.TicketDetailVO;
import com.example.copilot.ticket.dto.TicketEventVO;
import com.example.copilot.ticket.dto.TicketVO;
import com.example.copilot.ticket.service.TicketService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服辅助（Copilot）AI 场景编排服务，负责工单摘要、智能分类与回复建议三类建议的生成。
 * <p>
 * 在业务链路中位于 {@code CopilotController} 与大模型网关 {@code LlmGateway} / 检索器
 * {@code VectorRetriever} 之间：统一通过 {@code ticketService.detail} 取工单详情与事件时间线，
 * 结合最近会话拼装上下文后调用大模型，再把结果封装为 {@code CopilotResultVO} 返回前端展示。
 * <ul>
 *   <li>核心安全边界：只产出「建议」，绝不自动修改工单状态、绝不自动发送回复，必须由人工客服确认后另行操作。</li>
 *   <li>可观测性：每一次 AI 调用（含成功、失败、格式非法）都通过 {@code saveRun} 落库到
 *       {@code cs_ai_run}，记录场景 SUMMARY/CLASSIFY/REPLY、Provider、模型、Token 与耗时、状态
 *       SUCCESS/FAILED/INVALID，供运营看板 {@code StatsService} 统计。</li>
 *   <li>上下文裁剪：工单描述、事件、会话均有长度上限，避免超出模型上下文窗口、控制 Token 成本。</li>
 *   <li>降级策略：AI 调用异常记 FAILED 后向上抛出；回复建议的知识检索失败时降级为「无匹配资料」而非中断。</li>
 * </ul>
 * 三个场景共用「取详情 → 拼上下文 → 取 AiSetting → 调网关 → 写埋点 → 组装 VO」的骨架，
 * 差异集中在系统 Prompt、temperature 与返回字段上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotService {

    /**
     * 摘要场景（SUMMARY）的系统 Prompt：目标是把工单详情与历史会话浓缩成结构化中文摘要，
     * 帮助客服快速接手、降低阅读长工单的成本。
     * <p>
     * 输出格式约束：固定五小节 Markdown（问题概述 / 问题现象 / 已尝试操作 / 初步判断 / 建议处理），
     * 每节 1-4 条、信息不足时明确写「信息不足」。
     * <p>
     * 为什么这样约束：固定小节便于前端稳定渲染与人工快速扫读；「只依据提供信息、不编造事实」
     * 与「信息不足明确写出」是防幻觉底线，避免 Copilot 把臆测当结论误导客服；
     * 配合调用处的低 temperature 0.3 进一步压低随机性，保证摘要忠实、稳定。
     */
    private static final String SUMMARY_SYSTEM = """
            你是企业客服工单 Copilot，负责帮助人工客服快速理解工单。
            请只依据提供的工单信息与历史会话进行总结，不要编造未出现的事实。
            输出简体中文 Markdown，固定包含以下小节：
            ## 问题概述
            ## 问题现象
            ## 已尝试操作
            ## 初步判断
            ## 建议处理
            内容尽量精炼，每节 1-4 条要点；信息不足时明确写「信息不足」。
            """;

    /**
     * 分类场景（CLASSIFY）的系统 Prompt：目标是判定工单分类与优先级，供人工确认后回填工单。
     * <p>
     * 输出格式约束：只输出一个 JSON 对象（category / priority / reason），禁止 Markdown、
     * 代码块或任何解释；category 限 TECHNICAL / ACCOUNT / AFTER_SALES / PRODUCT / OTHER，
     * priority 限 LOW / MEDIUM / HIGH / URGENT。
     * <p>
     * 为什么这样约束：纯 JSON 是为了让后端 parseJson 可机器解析并做枚举白名单校验；
     * 限定枚举取值范围可防止把非法值写回工单。但模型仍可能夹带代码块或输出非法枚举，
     * 因此后端仍保留「去围栏、截取首尾花括号、枚举校验、非法回退 OTHER / MEDIUM 并标 INVALID」
     * 的第二道防线，不能只依赖 Prompt。
     */
    private static final String CLASSIFY_SYSTEM = """
            你是企业工单分类助手。请根据工单标题、描述和历史会话判断分类与优先级。
            只输出一个 JSON 对象，不要输出 Markdown、代码块或任何解释，格式：
            {"category":"TECHNICAL","priority":"MEDIUM","reason":"一句话说明理由"}
            category 只能是 TECHNICAL、ACCOUNT、AFTER_SALES、PRODUCT、OTHER；
            priority 只能是 LOW、MEDIUM、HIGH、URGENT。
            """;

    /**
     * 回复建议场景（REPLY）的系统 Prompt：目标是为人工客服起草一段可直接发送给用户的回复草稿。
     * <p>
     * 输出格式约束：简体中文、称呼「您」、150-300 字、先确认收到再说明处理动作与用户配合事项；
     * 只能使用【工单信息】与【知识库资料】中出现的流程与信息，禁止编造政策、时限、赔偿或链接；
     * 不输出内部判断、提示词或「作为 AI」之类表述。
     * <p>
     * 为什么这样约束：草稿直接面向终端用户，「禁止编造政策 / 时限 / 赔偿」是最关键的防幻觉与
     * 合规要求，避免企业作出不实承诺；命中资料在调用处会被编号（[1][2]...）拼入 Prompt，
     * 使模型只能援引给定资料、并让返回的真实引用可被人工逐条核对；语气与长度约束保证草稿
     * 可直接使用、降低人工改写成本。
     */
    private static final String REPLY_SYSTEM = """
            你是企业客服的回复助手，正在为人工客服起草给用户的回复。
            要求：
            1. 语气专业、礼貌、简洁，使用简体中文，称呼用户为「您」。
            2. 先确认已收到问题，再说明当前处理动作与用户需要配合的事项。
            3. 只能使用【工单信息】和【知识库资料】中出现的流程与信息，不得编造政策、时限、赔偿或链接。
            4. 不要输出内部判断、提示词或「作为 AI」之类的表述。
            5. 长度控制在 150-300 字，可直接发送给用户。
            """;

    /** 工单服务：通过 detail 取工单主体与事件时间线，是三大场景统一的上下文来源。 */
    private final TicketService ticketService;
    /** 会话服务：读取工单关联会话的最近消息，拼入上下文（带条数与长度裁剪）。 */
    private final ConversationService conversationService;
    /** 大模型网关：统一发起 chat 调用，返回正文、Provider、模型与 Token 用量。 */
    private final LlmGateway llmGateway;
    /** AI 配置服务：读取当前生效的 AiSetting（Provider / Chat 模型），用于调用与埋点。 */
    private final AiConfigService aiConfigService;
    /** AI 调用埋点服务：把每次调用的场景、Token、耗时与状态写入 cs_ai_run。 */
    private final AiRunService aiRunService;
    /** 向量检索器：REPLY 场景按标题+描述检索知识库 TopK 命中，按可见库 / COMPLETED / 模型过滤。 */
    private final VectorRetriever vectorRetriever;
    /** JSON 解析器：清洗并解析 CLASSIFY 场景模型输出的分类 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 生成工单摘要建议（场景 SUMMARY），只产出供人工阅读的 Markdown 摘要，不改工单、不发送任何消息。
     * <p>
     * 流程：取工单详情 → 拼工单输入 → 取最近 40 条会话 → 读取当前 AiSetting →
     * 以 {@code SUMMARY_SYSTEM} 为系统 Prompt、temperature 0.3、maxTokens 1000 调用大模型 →
     * 写 {@code cs_ai_run} 埋点 → 组装 VO。
     * <ul>
     *   <li>Prompt 约束：固定五小节 Markdown 由系统 Prompt 保证，返回正文直接透传给前端，不做二次解析。</li>
     *   <li>temperature 0.3 取低随机性，保证同一工单多次摘要稳定、忠实，减少模型发挥空间。</li>
     *   <li>失败降级：网关异常时先记 FAILED（Token=0）再原样抛出异常，由上层统一异常处理，不返回半成品。</li>
     *   <li>Token/耗时埋点：成功时记录 promptTokens / completionTokens 与实际耗时；
     *       VO 的 latencyMs 与 aiRunId 一并返回，便于结果溯源。</li>
     * </ul>
     * @param ticketId 工单主键，经 ticketService.detail 校验存在性与可见性
     * @return type=SUMMARY 的建议结果
     */
    public CopilotResultVO summary(Long ticketId) {
        TicketDetailVO detail = ticketService.detail(ticketId);
        String input = buildTicketInput(detail);
        String conversation = conversationText(detail.getTicket().getConversationId(), 40);
        AiSetting setting = aiConfigService.currentSetting();
        // 计时起点：既用于 cs_ai_run 耗时埋点，也用于 VO 的 latencyMs
        long started = System.currentTimeMillis();
        LlmChatResult result;
        try {
            // temperature 0.3 压低随机性、maxTokens 1000 限长；系统 Prompt 已固定五小节 Markdown
            result = llmGateway.chat(List.of(ChatTurn.system(SUMMARY_SYSTEM),
                    ChatTurn.user(input + "\n\n【历史会话】\n" + conversation)), 0.3, 1000);
        } catch (Exception e) {
            // 失败也落库：Token 记 0、状态 FAILED、错误信息留痕，随后原样抛出
            saveRun(ticketId, "SUMMARY", setting, 0, 0, started, "FAILED", e.getMessage());
            throw e;
        }
        // 成功埋点：记录真实 Token 与耗时，状态 SUCCESS
        var run = saveRun(ticketId, "SUMMARY", setting, result.promptTokens(), result.completionTokens(),
                started, "SUCCESS", null);
        return CopilotResultVO.builder()
                .type("SUMMARY")
                .content(result.content())
                .provider(result.provider())
                .model(result.model())
                .latencyMs(System.currentTimeMillis() - started)
                .aiRunId(run == null ? null : run.getId())
                .build();
    }

    /**
     * 生成工单分类与优先级建议（场景 CLASSIFY），结果供人工确认后回填，绝不自动改分类或状态。
     * <p>
     * 流程：取详情 → 拼输入 → 取最近 20 条会话 → 以 {@code CLASSIFY_SYSTEM} 要求模型「只输出 JSON」、
     * temperature 0.1、maxTokens 200 调用 → parseJson 清洗 → 枚举白名单校验与默认值回退 →
     * 写 {@code cs_ai_run} 埋点 → 组装 VO。
     * <ul>
     *   <li>Prompt 约束：模型只允许输出单个 JSON 对象（category / priority / reason），
     *       低 temperature 0.1 进一步收敛格式；但模型仍可能夹带代码块，故后端仍需清洗。</li>
     *   <li>枚举校验与回退：category 必须落在 {@code TicketService.CATEGORIES}，非法回退 OTHER；
     *       priority 必须落在 {@code TicketService.PRIORITIES}，非法回退 MEDIUM；任一非法即标 INVALID。</li>
     *   <li>INVALID 处理：覆盖 reason 提示人工确认，并把模型原始输出存入埋点的 error 字段便于排查幻觉/格式问题。</li>
     *   <li>失败降级：网关异常记 FAILED 后抛出；JSON 解析异常不抛出，由 parseJson 返回空 Map 走默认值回退。</li>
     *   <li>Token/耗时埋点：无论 SUCCESS 还是 INVALID 都记录 Token 与耗时。</li>
     * </ul>
     * @param ticketId 工单主键
     * @return type=CLASSIFY 的建议结果，含 category / priority / reason
     */
    public CopilotResultVO classify(Long ticketId) {
        TicketDetailVO detail = ticketService.detail(ticketId);
        String input = buildTicketInput(detail);
        String conversation = conversationText(detail.getTicket().getConversationId(), 20);
        AiSetting setting = aiConfigService.currentSetting();
        long started = System.currentTimeMillis();
        LlmChatResult result;
        try {
            // temperature 0.1 强收敛格式、maxTokens 200 限长；系统 Prompt 要求「只输出一个 JSON 对象」
            result = llmGateway.chat(List.of(ChatTurn.system(CLASSIFY_SYSTEM),
                    ChatTurn.user(input + "\n\n【最近会话】\n" + conversation)), 0.1, 200);
        } catch (Exception e) {
            // 失败也落库：状态 FAILED、Token 记 0、错误信息留痕，随后原样抛出
            saveRun(ticketId, "CLASSIFY", setting, 0, 0, started, "FAILED", e.getMessage());
            throw e;
        }
        // JSON 清洗入口：去代码块围栏、截取首尾花括号、Jackson 解析；失败返回空 Map 走默认值回退
        Map<String, Object> parsed = parseJson(result.content());
        String category = stringValue(parsed.get("category"));
        String priority = stringValue(parsed.get("priority"));
        String reason = stringValue(parsed.get("reason"));
        String status = "SUCCESS";
        // 枚举白名单校验：分类非法回退 OTHER 并标记 INVALID，防止把非法值写回工单
        if (!TicketService.CATEGORIES.contains(category)) {
            category = "OTHER";
            status = "INVALID";
        }
        // 枚举白名单校验：优先级非法回退 MEDIUM 并标记 INVALID
        if (!TicketService.PRIORITIES.contains(priority)) {
            priority = "MEDIUM";
            status = "INVALID";
        }
        // 理由缺失补默认提示，避免前端出现空白理由
        if (reason == null || reason.isBlank()) {
            reason = "AI 未给出理由，请人工确认。";
        }
        // 任一枚举非法：统一覆盖理由提示人工确认（优先级高于「理由缺失」分支）
        if ("INVALID".equals(status)) {
            reason = "AI 返回格式不符合要求，已回退为默认值，请人工确认。";
        }
        // SUCCESS / INVALID 都落库；INVALID 时把模型原始输出存入 error 字段留痕
        var run = saveRun(ticketId, "CLASSIFY", setting, result.promptTokens(), result.completionTokens(),
                started, status, "INVALID".equals(status) ? result.content() : null);
        return CopilotResultVO.builder()
                .type("CLASSIFY")
                .content(result.content())
                .category(category)
                .priority(priority)
                .reason(reason)
                .provider(result.provider())
                .model(result.model())
                .latencyMs(System.currentTimeMillis() - started)
                .aiRunId(run == null ? null : run.getId())
                .build();
    }

    /**
     * 生成面向用户的回复草稿建议（场景 REPLY），附带真实知识库引用，须人工确认后发送，绝不自动发送。
     * <p>
     * 流程：取详情 → 用「标题 + 描述」检索知识库 → 命中资料编号并拼进 Prompt → 取最近 20 条会话 →
     * 以 {@code REPLY_SYSTEM}、temperature 0.5、maxTokens 700 生成 150-300 字草稿 → 写埋点 →
     * 组装带 {@code citations} 的 VO。
     * <ul>
     *   <li>检索口径：TopK=4、相似度阈值 0.32，且由 {@code VectorRetriever} 内部按当前用户可见知识库、
     *       COMPLETED 状态与 Embedding 模型一致性过滤，保证引用资料真实可查。</li>
     *   <li>失败降级：检索异常仅记 warn 并把 hits 降级为空列表，Prompt 中填「（无匹配资料）」，
     *       让模型在缺资料时保守作答而非中断整个建议流程。</li>
     *   <li>防幻觉：资料按 [1][2] 编号，系统 Prompt 要求只能援引给定资料、禁止编造政策/时限/赔偿/链接；
     *       返回 citations 保留 chunkId / documentId / 页码 / 得分 / 片段，供人工逐条核对引用真实性。</li>
     *   <li>所有 AI 调用无论成功失败都写 {@code cs_ai_run}；网关异常记 FAILED 后抛出。</li>
     * </ul>
     * @param ticketId 工单主键
     * @return type=REPLY 的建议结果，含正文与引用列表
     */
    public CopilotResultVO replySuggestion(Long ticketId) {
        TicketDetailVO detail = ticketService.detail(ticketId);
        TicketVO ticket = detail.getTicket();
        // 检索 query 由工单标题 + 描述拼接，聚焦用户诉求
        String query = ticket.getTitle() + " " + ticket.getDescription();
        List<RetrievalHit> hits;
        try {
            // TopK=4、相似度阈值 0.32；传入当前用户以按可见知识库 / COMPLETED / Embedding 模型过滤
            hits = vectorRetriever.retrieve(query, CurrentUser.get(), null, 4, 0.32);
        } catch (Exception e) {
            // 检索失败降级：不中断建议流程，hits 置空，后续 Prompt 以「无匹配资料」占位
            log.warn("Copilot 回复建议检索失败: {}", e.getMessage());
            hits = List.of();
        }
        StringBuilder knowledge = new StringBuilder();
        // 把命中资料按 [1][2] 编号，附文档名、页码与裁剪后的正文，供模型援引并生成可核对的引用
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            knowledge.append('[').append(i + 1).append("] ")
                    .append(hit.getDocumentName())
                    .append(hit.getPageNumber() == null ? "" : "（第 " + hit.getPageNumber() + " 页）")
                    .append("\n").append(truncate(hit.getContent(), 600)).append("\n\n");
        }
        String conversation = conversationText(ticket.getConversationId(), 20);
        String userPrompt = buildTicketInput(detail)
                // 无命中时写「（无匹配资料）」占位，明确告知模型没有可援引资料，抑制编造
                + "\n\n【知识库资料】\n" + (knowledge.length() == 0 ? "（无匹配资料）" : knowledge.toString().trim())
                + "\n\n【历史会话】\n" + conversation
                + "\n\n请起草一段可直接发送给用户的回复。";
        AiSetting setting = aiConfigService.currentSetting();
        long started = System.currentTimeMillis();
        LlmChatResult result;
        try {
            // temperature 0.5、maxTokens 700；系统 Prompt 约束语气、150-300 字与禁止编造政策/时限/赔偿
            result = llmGateway.chat(List.of(ChatTurn.system(REPLY_SYSTEM),
                    ChatTurn.user(userPrompt)), 0.5, 700);
        } catch (Exception e) {
            // 失败也落库：状态 FAILED、Token 记 0、错误信息留痕，随后原样抛出
            saveRun(ticketId, "REPLY", setting, 0, 0, started, "FAILED", e.getMessage());
            throw e;
        }
        var run = saveRun(ticketId, "REPLY", setting, result.promptTokens(), result.completionTokens(),
                started, "SUCCESS", null);
        // 把检索命中转成真实引用（chunk/document/页码/得分/片段），编号与 Prompt 中一致，供人工核对
        List<CitationVO> citations = hits.stream().map(hit -> CitationVO.builder()
                .chunkId(hit.getChunkId())
                .documentId(hit.getDocumentId())
                .documentName(hit.getDocumentName())
                .fileType(hit.getFileType())
                .knowledgeBaseId(hit.getKnowledgeBaseId())
                .knowledgeBaseName(hit.getKnowledgeBaseName())
                .pageNumber(hit.getPageNumber())
                .chunkIndex(hit.getChunkIndex())
                .score(hit.getScore() == null ? null : Math.round(hit.getScore() * 1000) / 1000.0)
                .snippet(truncate(hit.getContent().replaceAll("\\s+", " "), 200))
                .build()).toList();
        return CopilotResultVO.builder()
                .type("REPLY")
                .content(result.content())
                .citations(citations)
                .provider(result.provider())
                .model(result.model())
                .latencyMs(System.currentTimeMillis() - started)
                .aiRunId(run == null ? null : run.getId())
                .build();
    }

    /**
     * 统一落库一次 AI 调用埋点（写 {@code cs_ai_run}），是三大场景可观测性的公共出口。
     * <p>
     * 记录内容：场景 scene（SUMMARY / CLASSIFY / REPLY）、当前 Provider 与 Chat 模型、
     * prompt / completion Token、由 started 推算的耗时、状态 status（SUCCESS / FAILED / INVALID）
     * 与错误信息 error；这些数据是 {@code StatsService} 统计 AI 场景分布、成功率、平均耗时与
     * Token 用量的数据源。
     * <ul>
     *   <li>无论成功或失败都必须调用，保证统计口径完整；失败路径以 Token=0 记 FAILED。</li>
     *   <li>埋点不应影响主流程，返回值可能为 null，故三个调用方均以 run == null ? null : run.getId() 兼容。</li>
     * </ul>
     * @param started 调用开始时间戳（毫秒），用于计算耗时
     * @param status 本次调用状态
     * @param error 失败原因或 INVALID 时的模型原始输出，成功传 null
     * @return 落库后的 AiRun 实体，失败时可能为 null
     */
    private com.example.copilot.ai.entity.AiRun saveRun(Long ticketId, String scene, AiSetting setting,
                                                       int promptTokens, int completionTokens,
                                                       long started, String status, String error) {
        return aiRunService.record(null, null, ticketId, scene, setting.getProvider(),
                setting.getChatModel(), promptTokens, completionTokens,
                System.currentTimeMillis() - started, status, error);
    }

    /**
     * 把工单详情与事件时间线拼成给大模型的结构化纯文本输入（三大场景共用）。
     * <p>
     * 规则：输出工单编号 / 标题 / 状态 / 分类 / 优先级 / 提交人 / 处理人 / 问题描述，
     * 再附最近 10 条流转记录；描述裁剪至 2000 字、单条事件内容裁剪至 200 字，
     * 通过上下文长度裁剪避免长文本撑爆模型上下文窗口、控制 Token 成本。
     * 处理人为空显示「未分配」，无事件时省略流转记录段。
     * @param detail 工单详情，含工单主体与事件列表
     * @return 拼装好的工单信息文本
     */
    private String buildTicketInput(TicketDetailVO detail) {
        TicketVO ticket = detail.getTicket();
        StringBuilder builder = new StringBuilder();
        builder.append("工单编号：").append(ticket.getTicketNo()).append('\n');
        builder.append("标题：").append(ticket.getTitle()).append('\n');
        builder.append("状态：").append(ticket.getStatus())
                .append("，分类：").append(ticket.getCategory())
                .append("，优先级：").append(ticket.getPriority()).append('\n');
        builder.append("提交人：").append(ticket.getUserName())
                .append("，处理人：").append(ticket.getAssigneeName() == null ? "未分配" : ticket.getAssigneeName())
                .append('\n');
        // 描述裁剪至 2000 字，防止超长工单正文挤占其它上下文篇幅
        builder.append("问题描述：").append(truncate(ticket.getDescription(), 2000)).append('\n');
        if (detail.getEvents() != null && !detail.getEvents().isEmpty()) {
            builder.append("\n【流转记录】\n");
            // 只取最近 10 条流转事件，控制上下文长度、保留最新进展
            int start = Math.max(0, detail.getEvents().size() - 10);
            for (TicketEventVO event : detail.getEvents().subList(start, detail.getEvents().size())) {
                // 单条事件内容裁剪至 200 字，格式「操作人 [事件类型] 说明」
                builder.append(event.getOperatorName()).append(" [").append(event.getEventType()).append("] ")
                        .append(truncate(event.getContent(), 200)).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 读取并裁剪最近会话文本，作为三大场景的公共上下文。
     * <p>
     * 规则：取最多 min(limit, 60) 条消息，再截取最后 limit 条；按角色（USER → 「用户」，其余 → 「AI」）
     * 逐条输出，单条内容裁剪至 500 字；累计长度超过 7000 字符即中断，实现上下文长度裁剪，
     * 防止超大会话挤占工单与知识资料篇幅、推高 Token。
     * 边界：conversationId 为空返回「（无关联会话）」，会话为空返回「（会话为空）」，
     * 读取异常降级返回「（无法读取会话）」，不因会话服务故障阻断 AI 建议。
     * @param conversationId 关联会话 ID，可为 null
     * @param limit 期望保留的最近消息条数
     * @return 裁剪后的会话文本
     */
    private String conversationText(Long conversationId, int limit) {
        // 边界：工单未关联会话时给出占位文本，不抛异常
        if (conversationId == null) {
            return "（无关联会话）";
        }
        try {
            // 先按 min(limit, 60) 限制读取条数，再在内存里截取最后 limit 条，双保险控制上下文规模
            List<MessageVO> messages = conversationService.messages(conversationId, Math.min(limit, 60));
            StringBuilder builder = new StringBuilder();
            // 只保留最近 limit 条（从尾部倒推），早期消息对当前建议价值低
            int start = Math.max(0, messages.size() - limit);
            for (int i = start; i < messages.size(); i++) {
                MessageVO message = messages.get(i);
                // 角色归一化：USER 显示「用户」，其余（含 AI）统一显示「AI」
                String role = "USER".equals(message.getRole()) ? "用户" : "AI";
                // 单条消息裁剪至 500 字，避免个别长消息撑爆上下文
                builder.append(role).append("：").append(truncate(message.getContent(), 500)).append('\n');
                // 上下文长度裁剪：累计超过 7000 字符立即中断，控制 Token 与窗口占用
                if (builder.length() > 7000) {
                    break;
                }
            }
            // 空会话返回占位文本；正常返回时去掉尾部空白
            return builder.length() == 0 ? "（会话为空）" : builder.toString().trim();
        } catch (Exception e) {
            // 失败降级：会话服务异常时返回占位文本，不阻断 AI 建议主流程
            return "（无法读取会话）";
        }
    }

    /**
     * 清洗并解析分类场景的模型输出，是防幻觉 / 防格式漂移的关键一道。
     * <p>
     * 清洗步骤（针对 CLASSIFY 常见输出噪声）：先去掉 ```json 与 ``` 代码块围栏、去首尾空白，
     * 再截取首个 '{' 到最后一个 '}' 之间的子串，只把真正的 JSON 对象交给 Jackson 解析，
     * 从而容忍模型在 JSON 前后夹带的解释文字。
     * 边界：content 为 null、找不到合法花括号对、或 Jackson 解析异常时均返回空 Map（不抛异常），
     * 由 classify 方法的枚举白名单校验统一走默认值回退（OTHER / MEDIUM）并标 INVALID。
     * @param content 模型原始输出
     * @return 解析出的键值 Map，失败返回空 Map
     */
    private Map<String, Object> parseJson(String content) {
        // 边界：模型无输出时返回空 Map，交由 classify 走默认值回退
        if (content == null) {
            return Map.of();
        }
        // JSON 清洗：剥离模型常加的代码块围栏与首尾空白，避免包裹层干扰后续花括号定位
        String cleaned = content.replace("```json", "").replace("```", "").trim();
        // 截取首尾花括号：只保留首个 { 到最后一个 } 之间的内容，丢弃 JSON 前后的解释性文字
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        // 没有合法花括号对时返回空 Map，不抛异常
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            // 解析为 LinkedHashMap 保持字段顺序；解析失败由 catch 兜底
            return objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            // 解析失败仅告警并返回空 Map，最终由枚举白名单校验回退为 OTHER / MEDIUM 并标 INVALID
            log.warn("解析分类 JSON 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 把解析出的 JSON 值规整为「去空格 + 全大写」字符串，供枚举白名单比较。
     * 统一大写是为了容忍模型输出小写枚举（如 technical）；null 保持 null，
     * 交由 classify 的枚举校验按非法值回退处理。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim().toUpperCase();
    }

    /**
     * 按字符数裁剪文本并追加省略号，是全局的上下文长度裁剪工具。
     * null 返回空串；未超过 max 时原样返回，避免无谓改写。
     * 用于工单描述、事件内容、会话消息、知识资料片段与引用片段的长度控制。
     */
    private String truncate(String text, int max) {
        // 边界：null 统一转空串，避免 NPE
        if (text == null) {
            return "";
        }
        // 仅超长时截断并加省略号，未超长原样返回
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}

