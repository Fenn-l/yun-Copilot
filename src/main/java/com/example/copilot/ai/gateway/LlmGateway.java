package com.example.copilot.ai.gateway;

import java.util.List;

/**
 * 大模型能力统一网关接口，是业务层访问 LLM 的唯一入口。
 * <p>为什么存在：把「业务要用 AI」与「具体由哪家模型、什么协议提供」彻底解耦。
 * 业务侧（RAG 问答、摘要、分类、Copilot 建议）只依赖本接口的 chat / embed /
 * embedBatch 与模型元信息方法，不直接引用 Spring AI、OpenAiApi 或任何厂商 SDK；
 * 因此可在阿里云百炼与本机 Ollama 之间运行时切换而不改业务代码，也便于在单测里
 * 用假实现替换，避免真实调用网络与消耗 Token。
 * <p>唯一实现：SpringAiLlmGateway，基于 Spring AI 1.0 的 OpenAI 兼容协议装配
 * OpenAiChatModel / OpenAiEmbeddingModel，运行时读 ai_setting 单行配置。
 * <p>关键约束：chat 与 embed 共用同一套当前配置，配置变更后须清空模型缓存；
 * 一旦切换 Embedding 模型或 embeddingDim，新旧向量维度不可比，必须重建全部向量索引，
 * 否则检索会因维度不匹配而失效。
 */
public interface LlmGateway {

    /**
     * 多轮对话补全：按给定消息列表生成回复，并返回正文、Token 与耗时。
     * <p>契约：messages 已含业务组装好的 System / 历史 / 知识库块 / 问题；
     * temperature 与 maxTokens 为本次单次覆盖值，优先级高于配置默认值
     * （RAG 问答固定 0.2 温度以抑制发散）；实现方负责把 ChatTurn 映射为供应商消息、
     * 从响应 Usage 统计 Token、把底层异常统一转为 502 BusinessException。
     *
     * @param messages    有序轮次列表，顺序即上下文顺序
     * @param temperature 采样温度，越低越确定
     * @param maxTokens   本次生成的最大 Token 上限
     * @return 统一结果，永不返回 null
     */
    LlmChatResult chat(List<ChatTurn> messages, double temperature, int maxTokens);

    /**
     * 单文本向量化：把一段文本映射为当前 Embedding 模型的浮点向量。
     * <p>契约：返回数组长度必须等于 embeddingDim()；知识入库切片与检索问题都走这里，
     * 保证写入与查询在同一向量空间；底层异常统一转 502。
     *
     * @param text 待向量化文本，不可为 null
     * @return 与当前配置维度一致的向量
     */
    float[] embed(String text);

    /**
     * 批量向量化：一次请求处理多条文本，用于知识库导入时减少网络往返。
     * <p>契约：返回结果数量与顺序必须与入参一一对应，数量不一致时实现方应报错，
     * 防止向量与切片错位；空列表直接返回空结果，不发起调用；异常统一转 502。
     *
     * @param texts 待向量化文本列表
     * @return 与 texts 等长且顺序一致的向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 返回当前生效的模型提供方标识（BAILIAN / OLLAMA）。
     * 供 cs_ai_run 记录与前端展示，切换配置后应即时反映新值。
     *
     * @return 当前提供方标识
     */
    String provider();

    /**
     * 返回当前生效的对话模型名，供调用记录与前端展示。
     *
     * @return 当前对话模型名
     */
    String chatModelName();

    /**
     * 返回当前生效的 Embedding 模型名。检索时按可见知识库 / COMPLETED / 当前
     * Embedding 模型过滤向量，本方法名是判定向量能否复用的关键依据之一。
     *
     * @return 当前 Embedding 模型名
     */
    String embeddingModelName();

    /**
     * 返回当前 Embedding 向量维度（百炼 text-embedding-v4 为 1024，Ollama 为 768）。
     * <p>向量索引按维度建列，切换模型导致维度变化时必须重建全部索引；
     * 配置为 null 时网关按默认值 1024 兜底。
     *
     * @return 当前向量维度
     */
    int embeddingDim();
}
