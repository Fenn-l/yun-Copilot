package com.example.copilot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片实体，对应表 kb_chunk，是 RAG 检索的最小单元：一段文本及其对应的向量。
 *
 * <p>在 RAG 链路中的位置：文档经解析后按段落聚合切片（约 420 字符、相邻切片 60 字符
 * 重叠、保留页码）逐条落库；检索时按「可见知识库 + 文档 COMPLETED + 当前 Embedding 模型」
 * 过滤，再对 embedding 做余弦相似度 TopK（默认 5、阈值 0.34）召回，后端清洗引用编号防幻觉。
 *
 * <p>关键设计：向量以 BLOB 与业务元数据同库存放在同一行，可随文档/知识库级联删除，
 * 用一次库内查询即可拿到文本、页码与向量，避免引入独立向量库带来的跨库一致性问题；
 * 但正因同库且模型可运行时切换（百炼 / Ollama），必须记录 embedding_model / embedding_dim ——
 * 不同模型维度与语义空间不同，切换后旧向量不可与新查询向量混算相似度，
 * 检索阶段据此只召回与当前模型一致的切片。
 *
 * <ul>
 *   <li>embedding 为 float32 数组经 EmbeddingCodec 按固定字节序序列化后的 BLOB，
 *       字节长度 = embeddingDim * 4；@JsonIgnore 防止大字段随接口序列化泄露。</li>
 *   <li>pageNumber 保留来源页码，供回答引用溯源；chunkIndex 保证原文顺序可还原。</li>
 * </ul>
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    /** 主键 ID，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 ID，外键关联 kb_document.id；按文档删除切片即以此列为条件。 */
    private Long documentId;

    /** 冗余所属知识库 ID，外键关联 kb_base.id；使检索可直接按可见知识库过滤，并支持按知识库批量删除。 */
    private Long knowledgeBaseId;

    /** 切片序号，从 0 递增，标识在文档中的顺序，保证含重叠的切片仍可还原原文次序。 */
    private Integer chunkIndex;

    /** 切片正文文本（约 420 字符，含与相邻切片 60 字符重叠），用于给大模型提供上下文。 */
    private String content;

    /** 文本向量，BLOB 存储；由 float32 数组经 EmbeddingCodec 序列化，长度 = embeddingDim * 4 字节；@JsonIgnore 避免接口泄露。 */
    @JsonIgnore
    private byte[] embedding;

    /** 向量维度，反序列化与余弦相似度计算的前置校验，防止不同维度向量混用。 */
    private Integer embeddingDim;

    /** 生成该向量的 Embedding 模型标识；检索按当前模型过滤，切换模型后旧向量不参与召回。 */
    private String embeddingModel;

    /** 来源页码（PDF 逐页抽取 / 解析器保留），回答引用溯源与引用展示用。 */
    private Integer pageNumber;

    /** 切片字符数，用于展示与切片质量、上限校验。 */
    private Integer charCount;

    /** 创建时间，插入时由 FieldFill.INSERT 自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
