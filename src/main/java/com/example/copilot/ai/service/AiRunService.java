package com.example.copilot.ai.service;

import com.example.copilot.ai.entity.AiRun;
import com.example.copilot.ai.mapper.AiRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 调用审计服务：把每次问答、转人工等调用写入 cs_ai_run，
 * 记录场景、提供方与模型、Prompt / Completion Token、耗时、状态与错误信息，
 * 并与会话、消息或工单关联，供 /api/ai/runs 查询与运营统计。
 *
 * <p>在 RAG 链路中的位置：AiChatService 的每个出口（正常、兜底、故障）都调用 record，
 * 是回答落库之后的最后一步观测动作。
 *
 * <p>关键设计：写入为尽力而为（best-effort），插入异常只告警不抛出，
 * 避免审计表故障反过来阻塞已经完成的 AI 回答；出参对象即便写失败也返回，
 * 使 ChatResponseVO.aiRunId 允许为空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRunService {

    // 仅依赖 cs_ai_run 的 Mapper，插入失败不影响主链路。
    private final AiRunMapper aiRunMapper;

    /**
     * 记录一次 AI 调用并落库，返回携带主键的 AiRun（供响应 aiRunId 使用）。
     *
     * <p>参数语义：scene 为场景（如 CHAT）；provider / model 为本次实际提供方与模型；
     * promptTokens / completionTokens 为输入输出 Token；latencyMs 为总耗时；
     * status 为 SUCCESS 或 FAILED；errorMessage 为故障信息，可空。
     *
     * <p>关联：conversationId 关联会话、messageId 关联 ASSISTANT 消息、ticketId 关联工单，
     * 问答场景 ticketId 传 null；三者共同支撑按会话或工单追溯调用链。
     *
     * <p>边界：Token 与耗时用 Math.max(x, 0) 防负值；错误信息截断 1000 字符防超长；
     * 插入异常只 warn 不抛，保证审计失败不阻塞业务，此时返回对象的 id 可能为空。
     */
    public AiRun record(Long conversationId, Long messageId, Long ticketId, String scene,
                        String provider, String model, int promptTokens, int completionTokens,
                        long latencyMs, String status, String errorMessage) {
        AiRun run = new AiRun();
        run.setConversationId(conversationId);
        run.setMessageId(messageId);
        run.setTicketId(ticketId);
        run.setScene(scene);
        run.setProvider(provider);
        run.setModelName(model);
        // Token 与耗时统一取非负下限，避免异常入参写出脏数据。
        run.setPromptTokens(Math.max(promptTokens, 0));
        run.setCompletionTokens(Math.max(completionTokens, 0));
        run.setLatencyMs(Math.max(latencyMs, 0));
        run.setStatus(status);
        // 错误信息截断 1000 字符，防止异常堆栈过长撑爆字段。
        run.setErrorMessage(errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage);
        // 审计写入为 best-effort：放在 try 中，任何异常都不向外传播。
        try {
            aiRunMapper.insert(run);
        // 仅告警不抛出：AI 回答已落库，审计表故障不应导致用户看到失败或触发重试。
        } catch (Exception e) {
            log.warn("写入 AI 调用记录失败: {}", e.getMessage());
        }
        // 无论 insert 成败都返回对象；失败时 id 为空，ChatResponseVO.aiRunId 允许为 null。
        return run;
    }
}
