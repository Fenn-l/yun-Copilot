package com.example.copilot.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 切片表 kb_chunk 的 MyBatis-Plus Mapper，继承 BaseMapper 获得单表 CRUD 能力。
 *
 * <p>在 RAG 链路中承载索引落库与清理：文档解析、切片、Embedding 完成后把一批 KbChunk
 * 批量写入；文档重处理或删除、知识库删除时按条件批量清理；检索阶段读取候选切片
 * （按可见知识库 + 文档 COMPLETED + 当前 Embedding 模型过滤）后计算余弦相似度 TopK。
 *
 * <p>关于批量写入/按文档删除/按知识库删除的意图与条件：本接口为空扩展、未声明自定义 SQL，
 * 这些操作由 Service 层组合 BaseMapper 的批量方法与 LambdaQueryWrapper 条件构造器完成：
 * <ul>
 *   <li>批量写入：Embedding 以 batch=10 分批调用，切片随之分批 insert；单条含
 *       embedding BLOB（维度 * 4 字节）体积较大，必须控制批次避免一次写入过多，
 *       且通常先清空该文档的旧切片再写入，配合 process_version 保证重处理幂等。</li>
 *   <li>按文档删除：条件为 document_id = 指定文档 ID，用于文档重处理（先删后写）
 *       与文档删除，避免旧切片残留污染检索结果。</li>
 *   <li>按知识库删除：条件为 knowledge_base_id = 指定知识库 ID，删除知识库时
 *       级联清理其全部切片；kb_chunk 冗余存储该列正是为支持这一条件直查与批量清理。</li>
 * </ul>
 * 上述删除条件必须精确限定到 document_id / knowledge_base_id，严禁无条件全表删除。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
