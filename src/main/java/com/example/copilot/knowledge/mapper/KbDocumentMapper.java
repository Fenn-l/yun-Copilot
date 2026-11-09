package com.example.copilot.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表 kb_document 的 MyBatis-Plus Mapper，继承 BaseMapper 获得单表 CRUD 能力。
 *
 * <p>在 RAG 链路中负责文档记录的落库与状态流转：UPLOADED → PROCESSING →
 * COMPLETED / FAILED，以及 chunk_count、embedding_model、embedding_dim、error_message 的更新。
 * 检索阶段据本表筛出「COMPLETED 且 embedding_model 与当前模型一致」的文档，
 * 其切片才进入余弦相似度召回。
 *
 * <p>本接口为空扩展、未声明自定义 SQL：按 knowledge_base_id 查询文档列表、带
 * process_version 条件的幂等状态更新（旧任务版本不匹配则不得覆盖新结果）等，
 * 均由 Service 层用条件构造器完成，避免把并发控制逻辑散落到 SQL 中。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
