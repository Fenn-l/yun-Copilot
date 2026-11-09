package com.example.copilot.copilot.dto;

import com.example.copilot.ai.dto.CitationVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 客服辅助（Copilot）三类建议的统一返回结果，用于 CopilotController 中
 * summary / classify / reply-suggestion 三个 POST 接口的响应体，外层由 ApiResponse 包装。
 * <p>
 * 设计上用一个 VO 承载三种场景：以 type 区分场景，各场景只填充自己关心的字段、其余为 null，
 * 避免为每种建议各建一个 DTO；所有内容均为「建议」，前端仅做展示，必须经人工确认后
 * 才会回填工单或发送给用户，VO 本身不触发任何写操作。
 * <ul>
 *   <li>SUMMARY：使用 content（固定五小节 Markdown 摘要）。</li>
 *   <li>CLASSIFY：额外使用 category / priority / reason。</li>
 *   <li>REPLY：额外使用 citations（真实知识库引用列表）。</li>
 * </ul>
 * provider / model / latencyMs / aiRunId 为三场景共用的可观测性字段，便于结果溯源与埋点核对。
 */
@Data
@Builder
public class CopilotResultVO {
    /** 建议场景类型：SUMMARY 摘要 / CLASSIFY 分类 / REPLY 回复建议。 */
    private String type;
    /** 建议正文：SUMMARY 为 Markdown 五小节摘要，CLASSIFY 为模型原始输出，REPLY 为 150-300 字回复草稿。 */
    private String content;
    /** 回复建议附带的真实知识库引用列表，仅 REPLY 场景有值，供人工逐条核对引用真实性。 */
    private List<CitationVO> citations;
    /** 分类建议的分类值，仅 CLASSIFY 场景有值，已通过枚举白名单校验（非法回退 OTHER）。 */
    private String category;
    /** 分类建议的优先级，仅 CLASSIFY 场景有值，已通过枚举白名单校验（非法回退 MEDIUM）。 */
    private String priority;
    /** 分类理由；当模型输出非法被判 INVALID 时改为人工确认提示，仅 CLASSIFY 场景有值。 */
    private String reason;
    /** 本次调用实际使用的大模型供应商（取自当前 AiSetting），用于结果溯源。 */
    private String provider;
    /** 本次调用实际使用的模型名，用于结果溯源。 */
    private String model;
    /** 本次 AI 调用耗时（毫秒），由发起网关调用处计时，用于前端展示与埋点核对。 */
    private Long latencyMs;
    /** 对应的 cs_ai_run 埋点主键，便于从建议结果反查本次调用的 Token 与状态明细。 */
    private Long aiRunId;
}
