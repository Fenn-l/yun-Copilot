package com.example.copilot.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG 问答接口的出参 VO，@Builder 由业务层组装返回。
 * <p>在 AI 链路中的位置：模型输出经后端清洗引用编号、裁剪上下文后得到 answer，
 * 连同引用列表、是否需要转人工、原因码与本次调用记录一并返回前端。
 * <p>设计意图：answer 与 citations 分离，引用只来自真实检索命中（CitationVO），
 * 前端可独立渲染来源，避免模型虚构引用；handoffRequired + reason 把降级决策显式化。
 * <p>原因码：USER_REQUEST 用户主动、INSUFFICIENT_KNOWLEDGE 知识不足、AI_UNAVAILABLE 模型不可用。
 */
@Data
@Builder
public class ChatResponseVO {
    /** 本次问答所属会话 ID。 */
    private Long conversationId;
    /** 本次保存的 ASSISTANT 消息 ID，反馈评价据此关联。 */
    private Long messageId;
    /** 最终回答：模型输出经清洗引用编号、裁剪上下文后的文本；知识不足 / 不可用时为兜底话术。 */
    private String answer;
    /** 引用列表，只来自真实向量检索命中，不含模型虚构来源；与 answer 中的 [编号] 对应。 */
    private List<CitationVO> citations;
    /** 是否需要转人工；知识不足、AI 不可用或用户主动请求时为 true。 */
    private boolean handoffRequired;
    /** 转人工原因码：USER_REQUEST / INSUFFICIENT_KNOWLEDGE / AI_UNAVAILABLE。 */
    private String reason;
    /** 关联的 cs_ai_run 记录 ID，便于追溯本次调用的 Token、耗时与状态。 */
    private Long aiRunId;
    /** 本次端到端耗时（毫秒）。 */
    private Long latencyMs;
    /** 本次实际提供方，来自运行时配置。 */
    private String provider;
    /** 本次实际对话模型名。 */
    private String model;
}
