package com.example.copilot.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对 AI 回答的评价，对应表 cs_feedback。
 * <p>在 AI 链路中的位置：用户对本轮 ASSISTANT 消息点赞 / 点踩时落一条，
 * 用于回答质量评估与后续优化；Copilot 建议须人工确认，反馈是人工判断的重要参考。
 * <p>枚举约定：rating 取 0（差评）或 1（好评），由 FeedbackRequest 的 @Min(0) @Max(1) 约束。
 */
@Data
@TableName("cs_feedback")
public class Feedback {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被评价的 ASSISTANT 消息 ID。 */
    private Long messageId;

    /** 所属会话 ID，便于按会话汇总反馈。 */
    private Long conversationId;

    /** 提交评价的用户 ID。 */
    private Long userId;

    /** 评分：0 差评、1 好评。 */
    private Integer rating;

    /** 可选文字评价，最长 1000 字符。 */
    private String comment;

    /** 评价时间，FieldFill.INSERT 提交时自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
