package com.example.copilot.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 运行时配置的单行实体，对应表 ai_setting。
 * <p>在 AI 链路中的位置：LlmGateway 的实现每次调用都通过 selectById(SINGLETON_ID) 读这一行，
 * 决定用哪个提供方、地址、密钥、对话 / 向量模型、维度与生成参数；系统设置页更新本表后
 * 需清空模型缓存，切换 Embedding 模型或维度后必须重建全部向量索引。
 * <p>设计要点：
 * <ul>
 *   <li>单行表：全表只允许一行配置，SINGLETON_ID 固定为 1，避免多套配置并存产生歧义；</li>
 *   <li>API Key 明文只入库、不回传：apiKey 上标注 @JsonIgnore，出参由 AiSettingVO.apiKeyMasked
 *       提供脱敏展示；密钥另按提供方存于 ai_provider_key，切回原供应商无需重填；</li>
 *   <li>provider 取值为 BAILIAN（阿里云百炼）或 OLLAMA（本机 Ollama），网关据此决定是否传
 *       dimensions 等供应商差异参数。</li>
 * </ul>
 */
@Data
@TableName("ai_setting")
public class AiSetting {

    /** 单行表固定主键：所有读写都用 selectById(1L)，保证全局只有一份生效配置。 */
    public static final long SINGLETON_ID = 1L;

    /** 主键，IdType.INPUT 表示由代码显式赋值，恒为 SINGLETON_ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 模型提供方标识：BAILIAN 或 OLLAMA；决定 baseUrl、可用模型、维度以及网关的兼容分支。 */
    private String provider;

    /** OpenAI 兼容服务根地址，百炼与本机 Ollama 各填各的端点。 */
    private String baseUrl;

    /**
     * 调用供应商的 API Key 明文，仅保存在数据库、不出现在任何出参。
     * <p>标注 @JsonIgnore：实体一旦被序列化（如误当接口返回）也不会带出密钥；
     * 系统设置页只通过 AiSettingVO.apiKeyMasked 返回脱敏值。
     * 另按提供方独立存于 ai_provider_key，切换供应商再切回时无需重新填写。
     */
    @JsonIgnore
    private String apiKey;

    /** 对话模型名，如 qwen-plus / deepseek-r1:8b，写进 cs_ai_run 便于按模型聚合。 */
    private String chatModel;

    /** Embedding 模型名，如 text-embedding-v4 / nomic-embed-text；检索仅复用同模型产出的向量。 */
    private String embeddingModel;

    /** 向量维度：百炼 1024、Ollama 768；切换模型或本值后必须重建全部向量索引，否则维度不匹配。 */
    private Integer embeddingDim;

    /** 默认采样温度，越低越确定；RAG 问答单次调用会用 0.2 覆盖此默认值。 */
    private Double temperature;

    /** 单次生成最大 Token 缺省值；调用方可按场景覆盖，限制成本与响应长度。 */
    private Integer maxTokens;

    /** 最近一次配置修改人 ID，用于配置变更审计。 */
    private Long updatedBy;

    /** 配置写入 / 更新时间，FieldFill.INSERT_UPDATE 自动填充，记录配置生效时点。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
