package com.example.copilot.ai.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 单条知识库引用 VO，@Builder 由检索命中结果组装。
 * <p>在 AI 链路中的位置：向量检索按余弦相似度取 TopK（默认 5、阈值 0.34）后，
 * 每条命中转成 CitationVO 返回前端，并与 PromptTemplates.buildContextBlock 生成的
 * [1]、[2] 编号及 RAG_SYSTEM 要求模型使用的 [编号] 一一对应。
 * <p>核心约束：本对象只能来自真实检索命中，绝不由模型生成。把引用来源收敛在后端命中结果，
 * 是防止模型虚构引用编号、编造制度条款与链接的关键，也让每条回答都可追溯到具体文档分片。
 */
@Data
@Builder
public class CitationVO {
    /** 命中的知识分片 ID，可据此回查原文。 */
    private Long chunkId;
    /** 分片所属文档 ID。 */
    private Long documentId;
    /** 文档名称，前端展示来源。 */
    private String documentName;
    /** 文档类型（如 PDF、Markdown），前端据此选展示方式。 */
    private String fileType;
    /** 所属知识库 ID，配合数据级鉴权校验用户可见范围。 */
    private Long knowledgeBaseId;
    /** 知识库名称，前端展示来源。 */
    private String knowledgeBaseName;
    /** PDF 等分页文档的页码，便于定位原文。 */
    private Integer pageNumber;
    /** 分片在文档内的序号。 */
    private Integer chunkIndex;
    /** 与问题的余弦相似度，高于阈值 0.34 才会进入命中列表。 */
    private Double score;
    /** 命中分片内容摘要，供前端在引用处展示来源片段。 */
    private String snippet;
}
