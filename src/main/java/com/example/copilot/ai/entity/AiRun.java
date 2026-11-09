package com.example.copilot.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次大模型调用的运行记录，对应表 cs_ai_run，是运营看板的数据来源。
 * <p>在 AI 链路中的位置：每次 chat / 摘要 / 分类 / 回复建议调用结束后落一条，
 * 记录场景、实际提供方与模型、Token、耗时、状态与错误摘要；AiRunMapper 的
 * dailyTrend / sceneStats / avgLatencySince / tokenSumSince 直接基于本表按时间窗聚合。
 * <p>枚举约定：
 * <ul>
 *   <li>scene：CHAT 问答、SUMMARY 会话摘要、CLASSIFY 分类、REPLY 回复建议；</li>
 *   <li>status：SUCCESS 成功、FAILED 调用失败、INVALID 结果无效；</li>
 *   <li>provider / modelName 为冗余快照，便于按当时的模型而非当前配置做历史聚合。</li>
 * </ul>
 * <p>该表是只增审计流水，不随后续配置变更回改，保证看板历史口径稳定。
 */
@Data
@TableName("cs_ai_run")
public class AiRun {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID，串联一次问答的多轮调用。 */
    private Long conversationId;

    /** 触发 / 对应本次调用的消息 ID（如保存的 ASSISTANT 消息）。 */
    private Long messageId;

    /** 关联工单 ID，转人工或工单场景回填，可为空。 */
    private Long ticketId;

    /** 调用场景：CHAT / SUMMARY / CLASSIFY / REPLY，看板按此分组统计。 */
    private String scene;

    /** 本次实际提供方（BAILIAN / OLLAMA）快照，便于按供应商聚合。 */
    private String provider;

    /** 本次实际对话模型名快照，便于按模型聚合成本与耗时。 */
    private String modelName;

    /** 提示词 Token 数，取自网关响应 Usage，缺失记 0。 */
    private Integer promptTokens;

    /** 生成 Token 数，取自网关响应 Usage，与 promptTokens 之和用于成本估算。 */
    private Integer completionTokens;

    /** 端到端耗时（毫秒），不含业务侧结果清洗与落库。 */
    private Long latencyMs;

    /** 调用状态：SUCCESS / FAILED / INVALID。 */
    private String status;

    /** 失败时的根因摘要（已截断），成功时为空；不存完整堆栈。 */
    private String errorMessage;

    /** 记录创建时间，FieldFill.INSERT 在调用结束落库时自动填充，看板按此过滤时间窗。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
