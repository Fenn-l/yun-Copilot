package com.example.copilot.ai.gateway;

/**
 * 一次大模型对话调用的统一结果（不可变值对象），屏蔽不同供应商响应结构的差异。
 * <p>在 AI 链路中的位置：由 SpringAiLlmGateway.chatWith 拿到 ChatResponse 后组装，
 * 返回给业务层保存 ASSISTANT 消息、写 cs_ai_run，并填充 ChatResponseVO 的耗时、
 * 提供方与模型字段。
 * <p>设计意图：把供应商相关的 ChatResponse / Usage 收敛成与 Spring AI 无关的简单
 * record，业务层因此不感知 OpenAI 协议细节，替换网关或做单测只需构造本对象；
 * Token 与耗时在此统一口径，供运营看板按模型、按提供方聚合成本。
 *
 * @param content          模型输出正文，结果判空时兜底空串，不返回 null
 * @param promptTokens     提示词 Token 数，取自响应 Usage，取不到兜底 0
 * @param completionTokens 生成 Token 数，取自响应 Usage，取不到兜底 0
 * @param latencyMs        端到端耗时（毫秒），从发起调用到拿到响应，不含结果清洗
 * @param model            本次实际对话模型名，冗余落库便于按模型聚合
 * @param provider         本次实际提供方，冗余落库便于按供应商聚合
 */
public record LlmChatResult(String content, int promptTokens, int completionTokens,
                            long latencyMs, String model, String provider) {
}
