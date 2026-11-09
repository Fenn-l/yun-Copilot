package com.example.copilot.ai.service;

import com.example.copilot.ai.dto.ChatRequest;
import com.example.copilot.ai.dto.ChatResponseVO;
import com.example.copilot.ai.dto.CitationVO;
import com.example.copilot.ai.entity.AiRun;
import com.example.copilot.ai.entity.AiSetting;
import com.example.copilot.ai.gateway.ChatTurn;
import com.example.copilot.ai.gateway.LlmChatResult;
import com.example.copilot.ai.gateway.LlmGateway;
import com.example.copilot.ai.prompt.PromptTemplates;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.conversation.entity.Conversation;
import com.example.copilot.conversation.entity.Message;
import com.example.copilot.conversation.service.ConversationService;
import com.example.copilot.knowledge.retrieval.RetrievalHit;
import com.example.copilot.knowledge.retrieval.VectorRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 问答主服务：编排校验会话、落用户消息、关键词转人工、向量检索、
 * 拼装上下文、调用大模型、引用防幻觉清洗、落助手消息、写调用审计的完整链路，
 * 是 POST /api/ai/chat 的核心实现。
 *
 * <p>主线步骤：
 * <ul>
 * <li>1. requireAccessible 校验会话归属与 ACTIVE 状态，非 ACTIVE 直接抛业务异常；</li>
 * <li>2. 先落库 USER 消息并首问自动改标题，再读取单行运行时配置 AiSetting；</li>
 * <li>3. 命中转人工关键词则直接返回转人工话术，不检索、不调模型；</li>
 * <li>4. VectorRetriever 按可见知识库、COMPLETED 文档、当前 Embedding 模型做余弦
 * TopK 检索，TopK 钳制为 1 到 10；检索异常或零命中走兜底；</li>
 * <li>5. buildTurns 拼 System 规则、最近历史、知识库编号资料块与用户问题，
 * LlmGateway.chat 以温度 0.2、maxTokens 钳制为 256 到 4096 调用模型；模型异常走兜底；</li>
 * <li>6. 空答案或含 INSUFFICIENT 标记走知识不足兜底；validateCitations 对答案做
 * 引用防幻觉清洗，清洗后正文为空同样兜底；</li>
 * <li>7. 正常分支落库清洗后的 ASSISTANT 消息与有效引用 JSON，cs_ai_run 记 SUCCESS；</li>
 * <li>8. 统一经 response 组装 ChatResponseVO，携带 handoffRequired 与 reason。</li>
 * </ul>
 *
 * <p>关键设计：
 * <ul>
 * <li>引用防幻觉：citations 只由本次真实检索命中生成，模型输出的 [编号] 仅作索引，
 * 越界编号从正文剔除，无有效编号时兜底返回前 3 条真实来源，
 * 体现模型输出不可信、后端必须校验；</li>
 * <li>上下文预算：历史跳过当前提问（因已先落库）、单条裁剪 700、资料块裁剪 800、
 * 资料总预算 maxContextChars 控制，防止超出模型上下文窗口；</li>
 * <li>降级策略：检索或模型异常一律降级为固定话术并置 handoffRequired=true，
 * 用户可直接据会话创建工单，保证 AI 故障不阻塞业务；</li>
 * <li>审计一致：每个出口都写 cs_ai_run，仅 AI_UNAVAILABLE 记 FAILED，
 * 业务兜底（关键词、无命中、知识不足、引用清洗为空）记 SUCCESS。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    // 引用编号正则：只识别方括号内 1 到 2 位数字（即 [1] 到 [99] 量级），
    // 用于从模型答案中提取 [编号] 并做范围校验。三位及以上编号（如 [100]）不会被匹配，
    // 会原样留在正文中；[0] 虽能被匹配，但会在范围校验时剔除。
    // 这是引用防幻觉的第一道闸门：正则限定了可校验的编号形态，避免模型任意编造来源编号。
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,2})]");
    // 转人工关键词集合：用户消息包含任一关键词时直接走转人工话术，
    // 属于业务意图识别，优先于检索与模型调用，零 Token、零延迟。
    private static final Set<String> HANDOFF_KEYWORDS = Set.of("转人工", "人工客服", "找人工", "真人客服");

    // 依赖分工：ConversationService 负责会话归属、消息落库、标题、最近历史；
    // VectorRetriever 负责知识库向量检索；LlmGateway 负责大模型调用；
    // AiRunService 负责写 cs_ai_run 审计；AiConfigService 提供单行运行时配置；
    // AppProperties 提供 historyLimit / topK / maxContextChars 等 RAG 预算参数。
    // 全部由 RequiredArgsConstructor 构造注入，便于替换与单测。
    private final ConversationService conversationService;
    private final VectorRetriever vectorRetriever;
    private final LlmGateway llmGateway;
    private final AiRunService aiRunService;
    private final AiConfigService aiConfigService;
    private final AppProperties properties;

    /**
     * RAG 问答主入口：按主线依次处理一次用户提问，并保证每个出口都落消息、写审计、返回统一 VO。
     *
     * <p>入参 ChatRequest 携带 conversationId、message、可选 knowledgeBaseId 与 topK；
     * 会话归属由 requireAccessible 做数据级鉴权，状态必须为 ACTIVE。
     *
     * <p>提前返回分支一览（消息 / reason / cs_ai_run 状态 / handoffRequired）：
     * <ul>
     * <li>关键词转人工：落 USER_REQUEST_ANSWER，reason=USER_REQUEST，SUCCESS，true；</li>
     * <li>检索异常：落 AI_UNAVAILABLE_ANSWER，reason=AI_UNAVAILABLE，FAILED，true，携带异常信息；</li>
     * <li>零命中：落 INSUFFICIENT_ANSWER，reason=INSUFFICIENT_KNOWLEDGE，SUCCESS，true；</li>
     * <li>模型异常：落 AI_UNAVAILABLE_ANSWER，reason=AI_UNAVAILABLE，FAILED，true，携带异常信息；</li>
     * <li>空答案或含 INSUFFICIENT 标记：落 INSUFFICIENT_ANSWER，reason=INSUFFICIENT_KNOWLEDGE，
     * SUCCESS，true，Token 用模型真实返回值；</li>
     * <li>引用清洗后正文为空：落 INSUFFICIENT_ANSWER，reason=INSUFFICIENT_KNOWLEDGE，
     * SUCCESS，true，Token 用模型真实返回值；</li>
     * <li>正常回答：落清洗后正文与有效引用，reason 为空，SUCCESS，false。</li>
     * </ul>
     *
     * <p>边界与异常：会话非 ACTIVE 抛 BusinessException；检索、模型调用分别 try-catch，
     * 异常一律降级不向上抛，保证 AI 故障不阻塞业务；审计写入由 AiRunService 尽力而为。
     *
     * @param request 用户提问与检索参数
     * @return 含回答、引用、是否需转人工、审计 id、耗时与模型的统一响应
     */
    public ChatResponseVO chat(ChatRequest request) {
        long started = System.currentTimeMillis();
        // 数据级鉴权：requireAccessible 校验当前用户可访问该会话，防止越权提问他人会话；
        // 随后校验状态必须为 ACTIVE，已结束会话不允许继续追问。
        Conversation conversation = conversationService.requireAccessible(request.getConversationId());
        // 会话已结束：直接抛业务异常，不落用户消息、不写审计，引导用户新建会话。
        if (!"ACTIVE".equals(conversation.getStatus())) {
            throw BusinessException.badRequest("会话已结束，请新建会话后继续提问");
        }
        String question = request.getMessage().trim();
        // 先落库 USER 消息，Token 数按字符数一半粗估（至少 1）；
        // 随后 updateTitleIfDefault 在首问时用问题内容自动改写默认标题。
        // 注意：此处先落库是后面 buildTurns 需要跳过当前提问的原因。
        Message userMessage = conversationService.appendMessage(conversation.getId(), "USER", question,
                Math.max(1, question.length() / 2), null, false, null);
        conversationService.updateTitleIfDefault(conversation.getId(), question);

        // 读取单行运行时配置（provider / chatModel / maxTokens 等），
        // 用于模型调用参数、审计记录与响应出参；配置由 AiConfigService 保证已初始化。
        AiSetting setting = aiConfigService.currentSetting();

        // 关键词转人工分支：命中转人工、人工客服、找人工、真人客服 时，
        // 直接落 USER_REQUEST_ANSWER 话术，reason=USER_REQUEST，handoffRequired=true；
        // 不检索、不调模型、Token 为 0，审计记 SUCCESS（属业务意图，非故障）。
        if (containsHandoffKeyword(question)) {
            Message assistant = conversationService.appendMessage(conversation.getId(), "ASSISTANT",
                    PromptTemplates.USER_REQUEST_ANSWER, 0, List.of(), true, "USER_REQUEST");
            AiRun run = aiRunService.record(conversation.getId(), assistant.getId(), null, "CHAT",
                    setting.getProvider(), setting.getChatModel(), 0, 0,
                    System.currentTimeMillis() - started, "SUCCESS", null);
            return response(conversation.getId(), assistant.getId(), PromptTemplates.USER_REQUEST_ANSWER,
                    List.of(), true, "USER_REQUEST", run, started, setting);
        }

        List<RetrievalHit> hits;
        // 向量检索：VectorRetriever 按当前用户可见知识库、COMPLETED 文档、
        // 当前 Embedding 模型做余弦相似度 TopK 检索（默认 5 条、阈值 0.34 由检索侧控制），
        // TopK 经 clampTopK 钳制为 1 到 10，防止过大上下文或检索开销。
        try {
            hits = vectorRetriever.retrieve(question, CurrentUser.get(), request.getKnowledgeBaseId(),
                    clampTopK(request.getTopK()), null);
        // 检索异常降级：catch 后统一走 handoff，落 AI_UNAVAILABLE_ANSWER 话术，
        // reason=AI_UNAVAILABLE，审计记 FAILED 并携带异常信息；
        // 不向上抛异常，用户仍可据此会话创建工单，业务流程不被 AI 故障阻塞。
        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", e.getMessage());
            return handoff(conversation.getId(), PromptTemplates.AI_UNAVAILABLE_ANSWER,
                    "AI_UNAVAILABLE", started, setting, e.getMessage());
        }

        // 零命中兜底：知识库没有可用资料，落 INSUFFICIENT_ANSWER 话术，
        // reason=INSUFFICIENT_KNOWLEDGE，审计记 SUCCESS（业务正常兜底，非故障），
        // handoffRequired=true 引导转人工。
        if (hits.isEmpty()) {
            return handoff(conversation.getId(), PromptTemplates.INSUFFICIENT_ANSWER,
                    "INSUFFICIENT_KNOWLEDGE", started, setting, null);
        }

        // 由本次真实检索命中生成候选引用列表，编号顺序与资料块 [1..N] 一一对应；
        // 这是引用防幻觉的数据源，最终对外引用只会是这个列表的子集。
        List<CitationVO> citations = hits.stream().map(this::toCitation).toList();
        // 拼装对话上下文：System 规则、最近历史（跳过当前提问）、知识库编号资料块与用户问题。
        List<ChatTurn> turns = buildTurns(conversation.getId(), userMessage.getId(), question, hits);

        LlmChatResult result;
        // maxTokens 钳制：未配置时默认 1200；否则限制在 256 到 4096，
        // 既保证回答有足够长度，又防止异常配置导致费用与延迟失控。
        int maxTokens = setting.getMaxTokens() == null ? 1200 : Math.max(256, Math.min(setting.getMaxTokens(), 4096));
        try {
            // 调用大模型：温度固定 0.2 以降低随机性、减少幻觉；maxTokens 使用上面的钳制结果。
            result = llmGateway.chat(turns, 0.2, maxTokens);
        // 模型异常降级：与检索异常一致，落 AI_UNAVAILABLE_ANSWER 话术，
        // reason=AI_UNAVAILABLE，审计记 FAILED 并携带异常信息，handoffRequired=true。
        } catch (Exception e) {
            log.warn("AI 问答调用失败: {}", e.getMessage());
            return handoff(conversation.getId(), PromptTemplates.AI_UNAVAILABLE_ANSWER,
                    "AI_UNAVAILABLE", started, setting, e.getMessage());
        }

        // 答案清洗：null 视为空串并 trim，后续统一按空答案或标记判定是否兜底。
        String answer = result.content() == null ? "" : result.content().trim();
        // 空答案或模型自报资料不足（含 INSUFFICIENT 标记）分支：
        // 落 INSUFFICIENT_ANSWER，reason=INSUFFICIENT_KNOWLEDGE，handoffRequired=true，
        // 审计记 SUCCESS；模型确已调用，故 Token 用 result 的真实值而非 0。
        if (answer.isBlank() || answer.contains(PromptTemplates.INSUFFICIENT_MARKER)) {
            Message assistant = conversationService.appendMessage(conversation.getId(), "ASSISTANT",
                    PromptTemplates.INSUFFICIENT_ANSWER, result.completionTokens(), List.of(),
                    true, "INSUFFICIENT_KNOWLEDGE");
            AiRun run = aiRunService.record(conversation.getId(), assistant.getId(), null, "CHAT",
                    setting.getProvider(), setting.getChatModel(), result.promptTokens(),
                    result.completionTokens(), System.currentTimeMillis() - started, "SUCCESS", null);
            return response(conversation.getId(), assistant.getId(), PromptTemplates.INSUFFICIENT_ANSWER,
                    List.of(), true, "INSUFFICIENT_KNOWLEDGE", run, started, setting);
        }

        // 引用防幻觉清洗入口：校验答案中的 [编号] 是否落在真实命中范围内，
        // 剔除越界编号、按有效编号重建引用列表；模型输出不可信，后端必须校验。
        CitationCheck check = validateCitations(answer, citations);
        // 清洗后正文为空（例如答案只由越界编号组成、剔除后无内容）：
        // 同样落 INSUFFICIENT_ANSWER 兜底，reason=INSUFFICIENT_KNOWLEDGE，
        // 审计记 SUCCESS，handoffRequired=true；Token 仍用模型真实值。
        if (check.answer().isBlank()) {
            Message assistant = conversationService.appendMessage(conversation.getId(), "ASSISTANT",
                    PromptTemplates.INSUFFICIENT_ANSWER, result.completionTokens(), List.of(),
                    true, "INSUFFICIENT_KNOWLEDGE");
            AiRun run = aiRunService.record(conversation.getId(), assistant.getId(), null, "CHAT",
                    setting.getProvider(), setting.getChatModel(), result.promptTokens(),
                    result.completionTokens(), System.currentTimeMillis() - started, "SUCCESS", null);
            return response(conversation.getId(), assistant.getId(), PromptTemplates.INSUFFICIENT_ANSWER,
                    List.of(), true, "INSUFFICIENT_KNOWLEDGE", run, started, setting);
        }

        // 正常回答分支：落库清洗后的正文与有效引用 JSON，
        // handoffRequired=false、reason 为空，审计记 SUCCESS；
        // 随后打日志记录引用数、耗时与 Token 消耗，便于观测与计费核对。
        Message assistant = conversationService.appendMessage(conversation.getId(), "ASSISTANT",
                check.answer(), result.completionTokens(), check.citations(), false, null);
        AiRun run = aiRunService.record(conversation.getId(), assistant.getId(), null, "CHAT",
                setting.getProvider(), setting.getChatModel(), result.promptTokens(),
                result.completionTokens(), System.currentTimeMillis() - started, "SUCCESS", null);
        log.info("AI 问答完成 conversation={} citations={} latency={}ms tokens={}/{}",
                conversation.getId(), check.citations().size(), System.currentTimeMillis() - started,
                result.promptTokens(), result.completionTokens());
        return response(conversation.getId(), assistant.getId(), check.answer(), check.citations(),
                false, null, run, started, setting);
    }

    /**
     * 统一降级出口：落一条 handoffRequired=true 的 ASSISTANT 消息、写 cs_ai_run、组装响应。
     *
     * <p>被三个分支复用：检索异常、模型异常（reason=AI_UNAVAILABLE）与零命中
     * （reason=INSUFFICIENT_KNOWLEDGE）。话术与 reason 由调用方传入，引用列表恒为空、Token 恒为 0。
     *
     * <p>状态规则：仅 reason=AI_UNAVAILABLE 记 FAILED（真实故障）；
     * INSUFFICIENT_KNOWLEDGE 属业务兜底记 SUCCESS。error 仅在异常时携带（已由 record 截断）。
     *
     * @param conversationId 会话 id
     * @param answer 固定兜底话术
     * @param reason 降级原因，决定审计状态与前端提示
     * @param started 起始时间，用于计算耗时
     * @param setting 当前运行时配置，用于审计与出参
     * @param error 异常信息，无异常传 null
     * @return 标记需转人工的降级响应
     */
    private ChatResponseVO handoff(Long conversationId, String answer, String reason, long started,
                                   AiSetting setting, String error) {
        Message assistant = conversationService.appendMessage(conversationId, "ASSISTANT", answer,
                0, List.of(), true, reason);
        AiRun run = aiRunService.record(conversationId, assistant.getId(), null, "CHAT",
                setting.getProvider(), setting.getChatModel(), 0, 0,
                System.currentTimeMillis() - started,
        // 审计状态：AI_UNAVAILABLE 是检索或模型故障记 FAILED；
        // 其余（业务兜底）记 SUCCESS，便于区分系统故障与正常知识不足。
                "AI_UNAVAILABLE".equals(reason) ? "FAILED" : "SUCCESS", error);
        return response(conversationId, assistant.getId(), answer, List.of(), true, reason, run, started, setting);
    }

    /**
     * 统一组装 ChatResponseVO：透传会话、消息 id、回答、有效引用、是否需转人工及原因，
     * 并带上审计记录 id、本次总耗时与 provider / model 供前端展示与问题排查。
     *
     * <p>边界：run 允许为 null（审计写入失败时 aiRunId 置空），
     * latencyMs 由 started 实时计算，所有出口复用此方法保证出参结构一致。
     */
    private ChatResponseVO response(Long conversationId, Long messageId, String answer,
                                    List<CitationVO> citations, boolean handoffRequired, String reason,
                                    AiRun run, long started, AiSetting setting) {
        return ChatResponseVO.builder()
                .conversationId(conversationId)
                .messageId(messageId)
                .answer(answer)
                .citations(citations)
                .handoffRequired(handoffRequired)
                .reason(reason)
                .aiRunId(run == null ? null : run.getId())
                .latencyMs(System.currentTimeMillis() - started)
                .provider(setting.getProvider())
                .model(setting.getChatModel())
                .build();
    }

    /**
     * 拼装送模对话：System 规则、最近历史，以及末尾一条拼接资料与问题的用户消息。
     *
     * <p>预算与边界：
     * <ul>
     * <li>历史取 recentMessages(historyLimit)，默认 8 轮；</li>
     * <li>跳过 currentUserMessageId：当前提问已先落库且末尾会再拼一次，避免重复；</li>
     * <li>空内容消息跳过；单条超过 700 字符裁剪并加省略号，控制上下文长度；</li>
     * <li>仅映射 USER / ASSISTANT 角色，其它角色忽略；</li>
     * <li>最后一轮用户消息由 buildUserPrompt 生成，含知识库编号资料块与用户问题。</li>
     * </ul>
     */
    private List<ChatTurn> buildTurns(Long conversationId, Long currentUserMessageId, String question,
                                      List<RetrievalHit> hits) {
        List<ChatTurn> turns = new ArrayList<>();
        turns.add(ChatTurn.system(PromptTemplates.RAG_SYSTEM));
        // 取最近 historyLimit 条历史（默认 8 轮），顺序由 ConversationService 保证。
        List<Message> history = conversationService.recentMessages(conversationId,
                properties.getRag().getHistoryLimit());
        for (Message message : history) {
            // 关键：跳过当前提问。它已在方法开头 appendMessage 落库，会出现在历史里；
            // 末尾又会以 buildUserPrompt 再拼一次（带资料块），不跳过会造成同一问题重复送模。
            if (message.getId().equals(currentUserMessageId)) {
                continue;
            }
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            // 单条历史裁剪 700 字符并加省略号，防止个别超长消息挤占资料预算。
            if (content.length() > 700) {
                content = content.substring(0, 700) + "...";
            }
            // 只把 USER / ASSISTANT 映射为对话轮次，SYSTEM 等其它角色不注入历史。
            if ("USER".equals(message.getRole())) {
                turns.add(ChatTurn.user(content));
            } else if ("ASSISTANT".equals(message.getRole())) {
                turns.add(ChatTurn.assistant(content));
            }
        }
        // 末尾追加真正送模的用户消息：知识库编号资料块、原始问题与引用作答要求。
        turns.add(ChatTurn.user(buildUserPrompt(question, hits)));
        return turns;
    }

    /**
     * 生成末尾用户消息：把检索命中拼成带编号的知识库资料块，再附用户问题与作答规则。
     *
     * <p>预算与边界：
     * <ul>
     * <li>编号从 [1] 开始，与 citations 列表下标加 1 对应，是引用防幻觉的编号基准；</li>
     * <li>每个资料块内容裁剪 800 字符；</li>
     * <li>总预算 maxContextChars，按块累加，超限即停止追加后续块；
     * used 大于 0 才中断，保证至少注入 1 块资料；</li>
     * <li>尾部要求模型严格依据资料、用 [编号] 标注引用，资料不足只输出 INSUFFICIENT 标记。</li>
     * </ul>
     */
    private String buildUserPrompt(String question, List<RetrievalHit> hits) {
        // 资料总预算来自配置 maxContextChars，控制送模上下文规模与费用。
        int budget = properties.getRag().getMaxContextChars();
        StringBuilder context = new StringBuilder();
        int used = 0;
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            String source = "来源：" + hit.getDocumentName()
                    + (hit.getPageNumber() == null ? "" : "（第 " + hit.getPageNumber() + " 页）");
            // 构造单个资料块：编号 [i+1]、来源（含可选页码）、内容（裁剪 800 字符）。
            // 编号必须与 citations 顺序一致，后续 validateCitations 才能按编号取回真实来源。
            String block = "[" + (i + 1) + "] " + source + "\n内容：" + truncate(hit.getContent(), 800);
            // 块级预算控制：累计长度超过 maxContextChars 就停止追加；
            // used 大于 0 才 break，确保即便第一块本身超预算也至少注入 1 块，避免零资料空问。
            if (used + block.length() > budget && used > 0) {
                break;
            }
            context.append(block).append("\n\n");
            used += block.length();
        }
        // 拼装提示词结构：编号资料块、用户问题、引用作答规则。
        // 明确要求用 [编号] 标注来源、资料不足只输出 INSUFFICIENT 标记，
        // 为后续后端校验引用和识别兜底提供约定。
        return "【知识库资料】\n" + context.toString().trim()
                + "\n\n【用户问题】\n" + question
                + "\n\n请严格依据资料作答，并用 [编号] 标注引用来源；资料不足时只输出 "
                + PromptTemplates.INSUFFICIENT_MARKER + "。";
    }

    /**
     * 引用防幻觉核心：清洗答案里的 [编号]，并按实际引用编号重建对外引用列表。
     *
     * <p>设计前提：citations 只来自本次真实检索命中，模型输出不可信，后端必须校验。
     * <ul>
     * <li>第一遍正则提取答案中所有编号（LinkedHashSet 去重且保序）；</li>
     * <li>第二遍重建正文：仅保留 1 到 citations.size() 范围内的编号，
     * 越界编号（以及被匹配到的 0 号）直接从正文剔除，并规范化为标准 [编号]；</li>
     * <li>从提取编号中筛出有效编号，按编号顺序取 citations 下标 index-1 生成最终列表；</li>
     * <li>无任何有效编号时兜底返回前 3 条真实来源，避免答案带引用标记却无来源；</li>
     * <li>返回清洗后正文，正文可能被剔除为空，由调用方再兜底。</li>
     * </ul>
     *
     * <p>边界：CITATION_PATTERN 只识别 1 到 2 位编号，三位及以上编号不会被剔除而原样保留；
     * 因此这是尽力而为的校验，配合 System 规则约束模型按资料作答。
     */
    private CitationCheck validateCitations(String answer, List<CitationVO> citations) {
        // 第一遍：用 CITATION_PATTERN 提取答案里所有 1 到 2 位编号，去重保序存入 referenced。
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        Set<Integer> referenced = new LinkedHashSet<>();
        while (matcher.find()) {
            referenced.add(Integer.parseInt(matcher.group(1)));
        }
        StringBuilder sanitized = new StringBuilder();
        // 第二遍：reset 后重建正文，逐个编号判断是否越界，越界则不在正文保留该编号。
        matcher.reset();
        int last = 0;
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            sanitized.append(answer, last, matcher.start());
            // 仅当编号落在 1 到真实命中数之间才写回标准 [编号]；
            // 越界编号（含 0 号）不 append，等价于从正文中剔除，杜绝指向不存在来源的引用。
            if (index >= 1 && index <= citations.size()) {
                sanitized.append('[').append(index).append(']');
            }
            last = matcher.end();
        }
        sanitized.append(answer.substring(last));
        // 从 referenced 中筛出有效编号（范围同上），LinkedHashSet 保持答案中首次出现顺序。
        Set<Integer> valid = new LinkedHashSet<>();
        for (Integer index : referenced) {
            if (index >= 1 && index <= citations.size()) {
                valid.add(index);
            }
        }
        List<CitationVO> used = new ArrayList<>();
        // 答案没有任何有效编号时，兜底返回前 3 条真实检索来源，
        // 保证前端总能拿到可追溯来源，同时不引入模型编造的内容。
        if (valid.isEmpty()) {
            used.addAll(citations.stream().limit(Math.min(3, citations.size())).toList());
        } else {
            // 按有效编号顺序（编号减 1 转下标）从本次命中中取回真实 CitationVO，重建引用列表。
            for (Integer index : valid) {
                used.add(citations.get(index - 1));
            }
        }
        // 返回清洗后正文（trim 后可能为空）与有效引用，交由 chat 决定正常返回或兜底。
        return new CitationCheck(sanitized.toString().trim(), used);
    }

    /**
     * 把一次检索命中转换为对外引用 VO，字段全部取自真实文档与分块元数据。
     *
     * <p>边界：score 为空则置空，否则四舍五入保留 3 位小数；
     * snippet 先把内容中的连续空白压缩为单空格再裁剪 220 字符，
     * 便于前端展示且不暴露整块原文。
     */
    private CitationVO toCitation(RetrievalHit hit) {
        return CitationVO.builder()
                .chunkId(hit.getChunkId())
                .documentId(hit.getDocumentId())
                .documentName(hit.getDocumentName())
                .fileType(hit.getFileType())
                .knowledgeBaseId(hit.getKnowledgeBaseId())
                .knowledgeBaseName(hit.getKnowledgeBaseName())
                .pageNumber(hit.getPageNumber())
                .chunkIndex(hit.getChunkIndex())
                .score(hit.getScore() == null ? null : Math.round(hit.getScore() * 1000) / 1000.0)
                .snippet(truncate(hit.getContent().replaceAll("\\s+", " "), 220))
                .build();
    }

    /**
     * 判断用户消息是否包含转人工关键词（子串匹配，任一命中即为 true）。
     * 仅做意图粗判，命中后 chat 直接短路返回，不进入检索与模型调用。
     */
    private boolean containsHandoffKeyword(String text) {
        return HANDOFF_KEYWORDS.stream().anyMatch(text::contains);
    }

    /**
     * TopK 钳制：请求未指定时取配置默认值（默认 5，不二次钳制）；
     * 指定时限制在 1 到 10，避免过大 TopK 撑爆上下文预算或拖慢检索。
     */
    private int clampTopK(Integer topK) {
        if (topK == null) {
            return properties.getRag().getTopK();
        }
        // 有值时钳制到 1 到 10；null 分支已在上方返回配置默认值。
        return Math.max(1, Math.min(topK, 10));
    }

    /**
     * 通用裁剪：null 返回空串；超过 max 时截断并追加省略号，否则原样返回。
     * 被历史单条（700）、资料块（800）、引用片段（220）等复用。
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    /**
     * 引用清洗结果载体：answer 为剔除越界编号后的正文，citations 为按有效编号重建的引用列表。
     * 作为内部 record 仅在本类 validateCitations 与 chat 之间传递，避免多返回值散落。
     */
    private record CitationCheck(String answer, List<CitationVO> citations) {
    }
}
