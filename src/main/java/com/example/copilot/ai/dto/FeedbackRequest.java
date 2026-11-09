package com.example.copilot.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户反馈接口的入参 DTO，落库为 cs_feedback。
 * <p>在 AI 链路中的位置：用户对某条 ASSISTANT 消息点赞 / 点踩时提交，
 * 用于回答质量评估；Copilot 建议须人工确认，反馈是人工判断的重要输入。
 * <p>约束：messageId 必填、rating 必填且只能是 0 或 1、comment 可选且不超过 1000 字符。
 */
@Data
public class FeedbackRequest {

    /** 被评价的 ASSISTANT 消息 ID，@NotNull。 */
    @NotNull(message = "消息 ID 不能为空")
    private Long messageId;

    /** 评分，@NotNull 且 @Min(0) @Max(1)：0 差评、1 好评。 */
    @NotNull(message = "评分不能为空")
    @Min(value = 0, message = "评分不合法")
    @Max(value = 1, message = "评分不合法")
    private Integer rating;

    /** 可选文字评价，@Size(max=1000)。 */
    @Size(max = 1000, message = "评价内容过长")
    private String comment;
}
