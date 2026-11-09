package com.example.copilot.ai.gateway;

/**
 * 单轮对话消息（不可变值对象），是业务层与大语言模型之间统一的消息载体。
 * <p>在 AI 链路中的位置：RAG 问答先把 System 规则、最近 8 条历史、知识库资料块、
 * 本轮问题依次转成 ChatTurn 列表，再交给 LlmGateway.chat 发给模型；
 * SpringAiLlmGateway.chatWith 再按 role 映射为 Spring AI 的 SystemMessage /
 * AssistantMessage / UserMessage。
 * <p>设计意图：用 record 而非普通类，天然不可变、自带 equals/hashCode，便于组装
 * 消息列表与单测断言；role 不用枚举而用字符串，是为了与 OpenAI 协议的 role 字面量
 * 直接对齐，避免额外转换。
 * <p>约定取值：system、user、assistant；未知角色在网关 switch 中一律降级为用户消息。
 *
 * @param role    消息角色，网关据此分支映射为对应 Spring AI Message
 * @param content 消息正文，由调用方保证不含越权内容
 */
public record ChatTurn(String role, String content) {

    /**
     * 构造 system 角色轮次，承载 RAG_SYSTEM 等系统级指令。
     * 工厂方法固定 role 字面量，避免调用方拼错角色字符串。
     *
     * @param content 系统提示词正文
     * @return role=system 的不可变轮次
     */
    public static ChatTurn system(String content) {
        return new ChatTurn("system", content);
    }

    /**
     * 构造 user 角色轮次，承载用户本轮问题或历史用户消息。
     *
     * @param content 用户消息正文
     * @return role=user 的不可变轮次
     */
    public static ChatTurn user(String content) {
        return new ChatTurn("user", content);
    }

    /**
     * 构造 assistant 角色轮次，承载历史对话中模型已给出的回复，回填多轮上下文。
     *
     * @param content 模型历史回复正文
     * @return role=assistant 的不可变轮次
     */
    public static ChatTurn assistant(String content) {
        return new ChatTurn("assistant", content);
    }
}
