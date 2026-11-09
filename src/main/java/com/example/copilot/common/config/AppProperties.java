package com.example.copilot.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用配置属性的类型安全绑定入口，绑定前缀 {@code app}，把 application.yml 中的
 * {@code app.*} 映射为强类型对象，供 JwtService、RAG 检索、Embedding、文件存储与演示数据等组件注入。
 *
 * <p>面试要点：相比散落的 {@code @Value}，{@code @ConfigurationProperties} 支持嵌套对象、
 * 字段默认值与松散绑定（relaxed binding），配合 {@code @Component} 直接注册为 Bean；
 * 所有默认值集中在此，改配置无需改代码。Lombok {@code @Data} 生成 setter，
 * 是 Spring Boot 能把配置值写入字段的前提。
 *
 * <p>分组：{@link Jwt} 令牌签名与有效期、{@link Storage} 上传根目录、
 * {@link Rag} 检索增强生成全链路参数、{@link Ai} 运行时大模型提供方、
 * {@link Seed} 演示数据开关。运行时切换百炼 / Ollama 即替换 {@link Ai} 下生效的 Provider。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** JWT 令牌配置分组。 */
    private Jwt jwt = new Jwt();
    /** 本地文件存储配置分组。 */
    private Storage storage = new Storage();
    /** RAG 检索增强生成参数分组，直接决定召回质量与上下文预算。 */
    private Rag rag = new Rag();
    /** 大模型提供方配置分组，支撑运行时在百炼与 Ollama 间切换。 */
    private Ai ai = new Ai();
    /** 演示数据开关分组，仅用于本地 / 演示环境初始化。 */
    private Seed seed = new Seed();

    /**
     * JWT 签名与有效期配置。
     */
    @Data
    public static class Jwt {
        /** HMAC-SHA 签名密钥；需满足 jjwt 最小长度（不小于 256 bit），泄露即可伪造任意用户令牌，生产应由环境变量注入而非写进配置文件。 */
        private String secret;
        /** 令牌有效期（小时），默认 24；签发时按签发时刻加该值得到 exp，越长越方便但被盗用窗口越大。 */
        private int expireHours = 24;
    }

    /**
     * 本地文件存储配置。
     */
    @Data
    public static class Storage {
        /** 上传文件落盘根目录，默认 {@code ./data/uploads}；文档解析任务据此读取原始 PDF / Word。 */
        private String localDir = "./data/uploads";
    }

    /**
     * RAG（检索增强生成）链路参数，直接影响召回质量与大模型上下文构造。
     *
     * <p>链路：PDFBox / POI 解析并保留页码 → 按 chunkSize / chunkOverlap 切成约 420 字符片段 →
     * Embedding 向量化并以 float32 序列化进 MySQL BLOB → 按「可见知识库 / 文档 COMPLETED /
     * 当前 Embedding 模型」三重过滤 → 余弦相似度取 topK → 按 scoreThreshold 去噪 →
     * 与最近 historyLimit 轮对话一起裁剪进 maxContextChars 的 Prompt。
     */
    @Data
    public static class Rag {
        /** 向量检索返回的最相似切片条数，默认 5；过小易漏召回，过大引入噪声并挤占上下文窗口。 */
        private int topK = 5;
        /** 余弦相似度下限，默认 0.34；低于该值的切片视为不相关被丢弃，是「知识不足则转人工」降级的第一道闸门。 */
        private double scoreThreshold = 0.34;
        /** 单切片约 420 字符，兼顾语义完整与检索粒度；过大稀释向量语义、过小丢失上下文。 */
        private int chunkSize = 420;
        /** 相邻切片重叠 60 字符，避免跨切片边界的语义被硬截断，需小于 chunkSize。 */
        private int chunkOverlap = 60;
        /** 拼入 Prompt 的检索上下文字符上限，默认 7000，防止超出模型上下文窗口与 Token 预算。 */
        private int maxContextChars = 7000;
        /** 多轮对话带入的最近消息条数，默认 8，只取窗口以控制 Token，不读取全量历史。 */
        private int historyLimit = 8;
        /** 单文件上传大小上限，默认 50MB，与 GlobalExceptionHandler 的 413 及 Spring multipart 限制呼应。 */
        private int maxUploadMb = 50;
    }

    /**
     * 大模型提供方配置，支持运行时在百炼（bailian）与本地 Ollama 之间切换。
     */
    @Data
    public static class Ai {
        /** 阿里云百炼（DashScope）提供方参数。 */
        private Provider bailian = new Provider();
        /** 本地 Ollama 提供方参数。 */
        private Provider ollama = new Provider();
    }

    /**
     * 单个模型提供方的连接与模型参数；两套 Provider 结构相同，便于网关按运行时配置热切换。
     */
    @Data
    public static class Provider {
        /** 调用鉴权密钥；Ollama 本地部署通常可留空。 */
        private String apiKey;
        /** 服务地址，Spring AI 的 OpenAI 兼容端点。 */
        private String baseUrl;
        /** 对话生成模型名，用于 RAG 问答与 Copilot 回复草稿。 */
        private String chatModel;
        /** 向量化模型名；切换后历史向量因「按当前 Embedding 模型过滤」自动失效，需重建索引。 */
        private String embeddingModel;
        /** 向量维度，须与 embeddingModel 输出一致，决定 BLOB 序列化长度与余弦计算的数组规模。 */
        private int embeddingDim;
        /** 单次生成最大 Token，默认 1500，约束回复长度与调用成本。 */
        private int maxTokens = 1500;
    }

    /**
     * 演示数据开关，仅作用于本地 / 演示环境初始化。
     */
    @Data
    public static class Seed {
        /** 总开关，默认 true；关闭后不执行任何演示数据初始化。 */
        private boolean enabled = true;
        /** 是否初始化演示知识库与文档，默认 true。 */
        private boolean knowledge = true;
    }
}
