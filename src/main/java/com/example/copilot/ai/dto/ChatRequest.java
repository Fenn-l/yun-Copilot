package com.example.copilot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * RAG 问答接口的入参 DTO（对应 AiController 的对话接口）。
 * <p>在 AI 链路中的位置：一次用户提问先落库为 USER 消息，再取最近 8 条历史、
 * 按可见知识库 / COMPLETED / 当前 Embedding 模型检索余弦 TopK 命中，
 * 拼好上下文后调用 LlmGateway.chat；本 DTO 只承载会话、问题及可选检索覆盖项。
 * <p>约束：conversationId 必填、message 必填且不超过 2000 字符；
 * knowledgeBaseId 与 topK 为可选覆盖，留空则由后端用默认可见范围、默认 TopK（5）
 * 与阈值（0.34）。
 */
@Data
public class ChatRequest {

    /** 会话 ID，@NotNull；据此读取最近 8 条历史并归属本轮消息。 */
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    /** 本轮问题，@NotBlank 且 @Size(max=2000)；保存为 USER 消息并作为检索 query。 */
    @NotBlank(message = "请输入问题")
    @Size(max = 2000, message = "问题内容过长")
    private String message;

    /** 可选：限定检索的知识库 ID；配合数据级鉴权，只在该库可见且已 COMPLETED 的文档中召回。 */
    private Long knowledgeBaseId;

    /** 可选：覆盖默认检索 TopK（默认 5）；最终还会受相似度阈值 0.34 过滤。 */
    private Integer topK;
}
