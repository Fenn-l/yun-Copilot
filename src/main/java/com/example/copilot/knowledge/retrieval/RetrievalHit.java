package com.example.copilot.knowledge.retrieval;

import lombok.Builder;
import lombok.Data;

/**
 * 一条向量检索命中结果（DTO / VO），是「检索」与「生成」之间的数据契约。
 *
 * <p>在 RAG 链路中的位置：由 {@link VectorRetriever} 对候选切片做余弦相似度打分、阈值过滤与 TopK 截断后生成。
 * 下游的问答/对话链路用它做两件事：把 {@code content} 作为证据拼进 Prompt，把
 * {@code documentName} / {@code pageNumber} / {@code chunkIndex} / {@code knowledgeBaseName} 组装成可核对的
 * 引用编号；后端据此清洗引用编号，防止模型编造不存在的出处（防幻觉）。
 *
 * <p>使用 Lombok {@code @Data} + {@code @Builder}，故只有字段声明、无显式构造器或 getter/setter。
 * 字段全部来自 kb_chunk / kb_document / kb_knowledge_base 三张表及打分结果。
 */
@Data
@Builder
public class RetrievalHit {
    /** 命中切片主键，来源 kb_chunk.id；用于按 chunk_id 去重与引用定位。 */
    private Long chunkId;
    /** 来源文档主键，来源 kb_chunk.document_id / kb_document.id。 */
    private Long documentId;
    /** 来源文档名称，来源 kb_document.document_name；前端展示引用标题。 */
    private String documentName;
    /** 文件类型（PDF / DOC / DOCX / TXT / MD），来源 kb_document.file_type，用于展示图标/来源说明。 */
    private String fileType;
    /** 所属知识库 ID，来源 kb_chunk.knowledge_base_id；已确保属于当前用户可见集合。 */
    private Long knowledgeBaseId;
    /** 所属知识库名称，来源 kb_knowledge_base.name；引用中标注来源库。 */
    private String knowledgeBaseName;
    /** 切片在文档内的序号，来源 kb_chunk.chunk_index（由 TextChunker 生成），用于引用排序与定位。 */
    private Integer chunkIndex;
    /** 页码，来源 kb_chunk.page_number（PDF 由 PDFBox 解析时保留）；纯文本类可能为 null，用于「第 N 页」引用。 */
    private Integer pageNumber;
    /** 切片正文，来源 kb_chunk.content；作为证据文本拼进 Prompt。 */
    private String content;
    /** 与问题向量的余弦相似度得分（越高越相关），由 {@link VectorRetriever} 计算；已通过阈值过滤与 TopK 截断。 */
    private Double score;
}
