package com.example.copilot.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 系统设置页更新 AI 配置的入参 DTO，写回 ai_setting 单行表。
 * <p>在 AI 链路中的位置：管理员保存配置后，service 固定主键为 SINGLETON_ID 写库、
 * 清空网关模型缓存使新配置生效；本 DTO 的校验保证进入网关的参数合法。
 * <p>关键规则：
 * <ul>
 *   <li>apiKey 可选，留空表示不覆盖、继续复用 ai_provider_key 中该 provider 已存的密钥，
 *       因此切换供应商回来无需重填；填入则更新对应提供方的密钥。</li>
 *   <li>provider 限定 BAILIAN / OLLAMA，baseUrl 与各模型名均必填。</li>
 *   <li>embeddingDim 一旦变更（如 1024 与 768 之间切换），除清缓存外必须重建全部向量索引，
 *       否则新旧向量维度不匹配会导致检索失效。</li>
 * </ul>
 */
@Data
public class UpdateAiSettingRequest {

    /** 模型提供方，@NotBlank；仅支持 BAILIAN / OLLAMA。 */
    @NotBlank(message = "请选择模型提供方")
    private String provider;

    /** OpenAI 兼容服务根地址，@NotBlank。 */
    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /** API Key，可选：为空沿用该 provider 已存密钥，非空则更新并写入 ai_provider_key。 */
    private String apiKey;

    /** 对话模型名，@NotBlank。 */
    @NotBlank(message = "对话模型不能为空")
    private String chatModel;

    /** Embedding 模型名，@NotBlank；变更需重建全部向量索引。 */
    @NotBlank(message = "Embedding 模型不能为空")
    private String embeddingModel;

    /** 向量维度，@NotNull 且 @Min(64) @Max(4096)；百炼 1024、Ollama 768，变更需重建索引。 */
    @NotNull(message = "向量维度不能为空")
    @Min(value = 64, message = "向量维度不合法")
    @Max(value = 4096, message = "向量维度不合法")
    private Integer embeddingDim;

    /** 默认采样温度，@Min(0) @Max(2)；RAG 问答单次调用以 0.2 覆盖。 */
    @Min(value = 0, message = "温度不合法")
    @Max(value = 2, message = "温度不合法")
    private Double temperature;

    /** 默认最大 Token，@Min(64) @Max(8192)。 */
    @Min(value = 64, message = "最大 Token 数不合法")
    @Max(value = 8192, message = "最大 Token 数不合法")
    private Integer maxTokens;
}
