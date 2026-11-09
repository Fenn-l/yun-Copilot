package com.example.copilot.knowledge.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 切片视图对象，用于「查看某文档的切片列表 / 检索命中结果展示」接口的响应体，对应 kb_chunk 表。
 *
 * <p>为何通常不含向量原文：embedding 是 float32 数组序列化后的 BLOB（字节数 = 维度 * 4，
 * 上千维即每条约数 KB），切片列表一次可能返回成百上千条，带上向量会极大放大响应体、
 * 拖慢传输且对前端毫无展示价值，还有泄露模型向量与 aiding 攻击面的风险；
 * 因此实体 KbChunk 用 @JsonIgnore 屏蔽，本 VO 干脆不定义该字段。相似度计算与召回全部在
 * 后端完成，前端只需 content / pageNumber / chunkIndex 做「原文片段 + 引用页码」的溯源展示。
 */
@Data
@Builder
public class ChunkVO {
    /** 切片主键 ID，对应 kb_chunk.id。 */
    private Long id;
    /** 所属文档 ID，外键关联 kb_document.id。 */
    private Long documentId;
    /** 切片序号，从 0 递增，标识在文档中的顺序。 */
    private Integer chunkIndex;
    /** 来源页码，回答引用溯源与展示用（PDF 逐页抽取 / 解析器保留）。 */
    private Integer pageNumber;
    /** 切片正文文本，约 420 字符且含 60 字符重叠。 */
    private String content;
    /** 切片字符数，用于展示与切片质量校验。 */
    private Integer charCount;
}
