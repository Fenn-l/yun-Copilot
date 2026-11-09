package com.example.copilot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体，对应表 kb_document，记录一次上传的原始文件及其解析、切片、向量化进度。
 *
 * <p>在 RAG 链路中的位置：PDF/DOC/DOCX/TXT/MD（≤50MB）上传后先由 LocalFileStorage
 * 本地落盘，再写入本表一条 UPLOADED 记录；随后由异步线程池解析、按段落切片、调用
 * Embedding，切片结果写入 kb_chunk。状态沿 UPLOADED → PROCESSING → COMPLETED / FAILED
 * 单向流转，检索只召回 COMPLETED 且 embedding_model 与当前 Embedding 模型一致的文档切片。
 *
 * <ul>
 *   <li>status 四值：UPLOADED 已上传待处理 / PROCESSING 解析与向量化中 /
 *       COMPLETED 处理完成可检索 / FAILED 失败（原因见 errorMessage）。</li>
 *   <li>process_version 控制重处理幂等：每次发起处理版本递增，旧任务的完成结果
 *       不得覆盖新版本的结果，避免并发重处理把新切片写脏或把状态回退。</li>
 *   <li>embedding_model / embedding_dim 标记本批切片所用模型与维度：运行时可切换
 *       百炼 / Ollama，切换后旧向量不可与新查询向量混算相似度。</li>
 * </ul>
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 主键 ID，自增；作为 kb_chunk.document_id 的外键关联对象。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属知识库 ID，外键关联 kb_base.id；决定文档的可见范围与检索过滤边界。 */
    private Long knowledgeBaseId;

    /** 原始文件名，列表展示与下载用。 */
    private String documentName;

    /** 文件本地落盘的相对路径/URL，由 LocalFileStorage 按知识库分目录 + 随机文件名生成。 */
    private String fileUrl;

    /** 文件扩展名类型（pdf/doc/docx/txt/md），DocumentParserRegistry 据此路由解析器。 */
    private String fileType;

    /** 文件字节数，用于 50MB 上传上限校验与展示。 */
    private Long fileSize;

    /** 处理状态枚举：UPLOADED / PROCESSING / COMPLETED / FAILED（四值）；仅 COMPLETED 参与检索。 */
    private String status;

    /** 处理失败原因，FAILED 时记录异常信息，便于排查与前端展示。 */
    private String errorMessage;

    /** 该文档最终生成的切片数量，与 kb_chunk 实际行数一致，用于进度展示与结果校验。 */
    private Integer chunkCount;

    /** 处理版本号，控制重处理幂等；旧任务结果不能覆盖新版本结果。 */
    private Integer processVersion;

    /** 本批切片使用的 Embedding 模型标识；检索按当前模型过滤，切换模型后旧向量不参与召回。 */
    private String embeddingModel;

    /** 向量维度，与 EmbeddingCodec 的 float32 数组长度一致，供反序列化与相似度计算前置校验。 */
    private Integer embeddingDim;

    /** 创建时间，插入时由 FieldFill.INSERT 自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时由 FieldFill.INSERT_UPDATE 自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
