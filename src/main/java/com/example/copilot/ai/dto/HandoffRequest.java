package com.example.copilot.ai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 转人工接口的入参 DTO，用于创建工单。
 * <p>在 AI 链路中的位置：用户主动请求（USER_REQUEST）、知识不足（INSUFFICIENT_KNOWLEDGE）
 * 或 AI 不可用（AI_UNAVAILABLE）时触发，把会话转成人工工单，进入工单六态状态机。
 * <p>约束：conversationId 必填，title 不超过 200 字符、description 不超过 2000 字符；
 * Copilot 给出的建议须人工确认后才会落地，不能自动改变工单状态。
 */
@Data
public class HandoffRequest {

    /** 要转人工的会话 ID，@NotNull；据此带出上下文填充工单描述。 */
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    /** 工单标题，@Size(max=200)。 */
    @Size(max = 200, message = "标题过长")
    private String title;

    /** 工单分类，用于路由到对应处理组。 */
    private String category;

    /** 工单优先级。 */
    private String priority;

    /** 问题描述，@Size(max=2000)，通常由会话上下文预填。 */
    @Size(max = 2000, message = "描述过长")
    private String description;
}
