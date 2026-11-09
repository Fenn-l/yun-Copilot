package com.example.copilot.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统设置页的 AI 配置出参 VO，@Builder 由 service 组装。
 * <p>在 AI 链路中的位置：回显 ai_setting 单行配置，并提供各供应商预设供管理员切换。
 * <p>核心约束：API Key 在出参始终脱敏。实体 apiKey 标有 @JsonIgnore，
 * 本 VO 只暴露 apiKeyMasked 掩码值，明文密钥永不返回前端，
 * 防止通过配置回显接口泄漏凭证。
 */
@Data
@Builder
public class AiSettingVO {
    /** 当前提供方：BAILIAN 或 OLLAMA。 */
    private String provider;
    /** OpenAI 兼容服务根地址。 */
    private String baseUrl;
    /** 脱敏后的 API Key 展示值；明文永不回传前端，仅用于回显是否已配置。 */
    private String apiKeyMasked;
    /** 当前对话模型名。 */
    private String chatModel;
    /** 当前 Embedding 模型名。 */
    private String embeddingModel;
    /** 当前向量维度；修改后需重建全部向量索引。 */
    private Integer embeddingDim;
    /** 默认采样温度。 */
    private Double temperature;
    /** 默认单次最大 Token。 */
    private Integer maxTokens;
    /** 最近一次配置更新时间，来自 ai_setting 的自动填充字段。 */
    private LocalDateTime updateTime;
    /** 按 provider 分组的预设参数（BAILIAN / OLLAMA 的默认地址、模型与维度），前端切换供应商时带出。 */
    private Map<String, Map<String, Object>> presets;
}
