package com.example.copilot.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档视图对象，用于文档列表、详情、上传处理进度查询接口的响应体，对应 kb_document 表。
 *
 * <p>前端据 status 与 errorMessage 轮询异步处理进度：UPLOADED 已上传 / PROCESSING 处理中 /
 * COMPLETED 可检索 / FAILED 失败。knowledgeBaseName 为关联 kb_base 得到的展示字段。
 *
 * <p>安全与边界：刻意不暴露 fileUrl（服务器落盘相对路径，避免路径泄露）与 processVersion
 * （重处理幂等的内部控制字段，不应对前端呈现）；embeddingModel / embeddingDim 用于展示
 * 该文档切片所用的向量模型与维度，帮助理解「切换模型后旧向量不可混用」的检索过滤规则。
 */
@Data
@Builder
public class DocumentVO {
    /** 文档主键 ID，对应 kb_document.id。 */
    private Long id;
    /** 所属知识库 ID，外键关联 kb_base.id。 */
    private Long knowledgeBaseId;
    /** 所属知识库名称，关联查询得到的展示字段。 */
    private String knowledgeBaseName;
    /** 原始文件名。 */
    private String documentName;
    /** 文件扩展名类型（pdf/doc/docx/txt/md）。 */
    private String fileType;
    /** 文件字节数，用于展示与 50MB 上限提示。 */
    private Long fileSize;
    /** 处理状态枚举：UPLOADED / PROCESSING / COMPLETED / FAILED（四值）。 */
    private String status;
    /** 处理失败原因，FAILED 时展示给前端便于排查。 */
    private String errorMessage;
    /** 该文档生成的切片数量，等于 kb_chunk 中该文档的行数。 */
    private Integer chunkCount;
    /** 该文档切片使用的 Embedding 模型标识，切换模型后旧切片不参与召回。 */
    private String embeddingModel;
    /** 向量维度，与 float32 数组长度一致。 */
    private Integer embeddingDim;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
