package com.example.copilot.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.knowledge.chunk.TextChunk;
import com.example.copilot.knowledge.embedding.EmbeddingCodec;
import com.example.copilot.knowledge.entity.KbChunk;
import com.example.copilot.knowledge.entity.KbDocument;
import com.example.copilot.knowledge.mapper.KbChunkMapper;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 索引落库器：把一批切片及其向量在【单个事务】内原子写入 kb_chunk，并把文档推进到 COMPLETED。
 *
 * <p>在 RAG 链路中的位置：它是「解析 / Embedding（事务外）」与「落库（事务内）」的分界点。
 * {@link DocumentProcessService} 把所有耗时远程调用放在事务外，最后才调用本类的 {@link #save}，
 * 用尽可能短的事务完成 DB 写入，避免长事务持有连接。
 *
 * <p>关键设计——为什么需要「二次校验版本与状态」：
 * 从任务抢占 PROCESSING 到真正写库之间，可能经历数十秒的解析与向量化，这段窗口内用户完全可能
 * 触发 reprocess（版本 +1）或 delete。若不做二次校验，旧任务会把自己算出的旧向量写回去，覆盖新任务
 * 的结果或复活已删文档。因此在写库前【再次】校验文档存在、process_version 与传入值一致，且最终的
 * 状态更新仍带版本条件；任一不满足即抛 {@link StaleIndexException}，让旧结果被事务回滚并丢弃。
 *
 * <p>其它约束：
 * <ul>
 *   <li>{@code @Transactional} 保证「删旧切片 → 批量写新切片 → 更新文档状态」要么全成要么全滚，
 *       不会出现半套索引或状态与切片数不一致。</li>
 *   <li>向量经 {@link EmbeddingCodec} 编码为 float32 字节数组，存入 kb_chunk.embedding 这个 BLOB 字段；
 *       同时冗余 embedding_dim / embedding_model，供检索按模型与维度过滤。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexWriter {

    /** 文档表 Mapper：二次校验版本/状态，并以带版本条件的 update 推进 COMPLETED。 */
    private final KbDocumentMapper documentMapper;
    /** 切片表 Mapper：删除旧切片、批量插入新切片。 */
    private final KbChunkMapper chunkMapper;
    /** 向量编解码器：float[] 与 float32 BLOB 字节相互转换。 */
    private final EmbeddingCodec embeddingCodec;

    /**
     * 「索引结果已过期」信号：写库前的二次校验发现文档被删除、版本变化或状态被抢占时抛出。
     *
     * <p>语义：它表示「本次异步任务算出的向量已经不该被写入」，属于正常的并发淘汰，而非处理失败。
     * 由 {@link DocumentProcessService#process} 单独捕获后只告警、直接丢弃，不会把文档写为 FAILED，
     * 以免覆盖正在执行的新任务状态。继承 RuntimeException 是为了能在 {@code @Transactional} 内触发回滚。
     */
    public static class StaleIndexException extends RuntimeException {
        /**
         * 构造过期异常，消息用于说明过期原因（如「文档处理版本已变化」），便于日志排查。
         *
         * @param message 过期原因描述
         */
        public StaleIndexException(String message) {
            super(message);
        }
    }

    /**
     * 在单个事务内完成索引替换：二次校验 → 删旧切片 → 写新切片 → 推进文档 COMPLETED。
     *
     * <p>执行顺序与边界：
     * <ol>
     *   <li>二次校验：按 ID 重查文档，不存在抛 {@link StaleIndexException}（"文档已被删除"）；
     *       process_version 与传入不符抛 Stale（"版本已变化"）。这一步是防旧覆盖的第一道闸。</li>
     *   <li>按 document_id 删除旧切片：重处理时先清掉上一版索引，保证库里只留当前版本的向量。</li>
     *   <li>逐条写新切片：冗余 knowledge_base_id、chunk_index、page_number、char_count，
     *       并把向量编码成 float32 BLOB（embedding），附带 embedding_dim / embedding_model。</li>
     *   <li>带版本条件的 update 推进 COMPLETED + chunk_count + 模型/维度 + 清空 errorMessage。
     *       rows == 0 抛 Stale（"状态已变化，放弃写入"）——这是二次校验最关键的一道：它发生在所有切片写完之后，
     *       一旦版本被改，异常会让整个事务回滚，连刚写入的切片一并撤销，绝不留半成品。</li>
     * </ol>
     *
     * @param documentId     文档主键
     * @param processVersion 本任务的处理版本号，作为二次校验与最终更新的条件
     * @param chunks         切片列表（含正文、序号、页码）
     * @param vectors        与切片一一对应的向量列表（调用方已校验数量与维度）
     * @param embeddingModel 本次使用的 Embedding 模型名
     * @param dimension      向量维度
     * @return 写入的切片数量（即 chunks.size()）
     * @throws StaleIndexException 文档被删、版本/状态在写入前变化时抛出，触发事务回滚
     */
    @Transactional
    public int save(Long documentId, int processVersion, List<TextChunk> chunks, List<float[]> vectors,
                    String embeddingModel, int dimension) {
        KbDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new StaleIndexException("文档已被删除");
        }
        // 二次校验版本：解析/向量化耗时窗口内若发生 reprocess，版本已 +1，旧结果必须作废。
        if (!Objects.equals(document.getProcessVersion(), processVersion)) {
            throw new StaleIndexException("文档处理版本已变化");
        }
        chunkMapper.delete(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocumentId, documentId));
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            KbChunk entity = new KbChunk();
            entity.setDocumentId(documentId);
            // 冗余知识库 ID：检索时直接按 knowledge_base_id 做 IN 过滤，无需回表关联文档。
            entity.setKnowledgeBaseId(document.getKnowledgeBaseId());
            entity.setChunkIndex(chunk.index());
            entity.setContent(chunk.content());
            // float[] 经 EmbeddingCodec 编码为 float32 BLOB 存储，读取时用同一编解码器还原。
            entity.setEmbedding(embeddingCodec.toBytes(vectors.get(i)));
            entity.setEmbeddingDim(dimension);
            entity.setEmbeddingModel(embeddingModel);
            entity.setPageNumber(chunk.pageNumber());
            entity.setCharCount(chunk.content().length());
            chunkMapper.insert(entity);
        }
        // 最终状态更新仍带版本条件：这是二次校验的最后一道，也是触发整体回滚的关键点。
        int rows = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessVersion, processVersion)
                .set(KbDocument::getStatus, "COMPLETED")
                .set(KbDocument::getChunkCount, chunks.size())
                .set(KbDocument::getEmbeddingModel, embeddingModel)
                .set(KbDocument::getEmbeddingDim, dimension)
                .set(KbDocument::getErrorMessage, null));
        if (rows == 0) {
            // 版本在写入窗口内被改：抛异常让 @Transactional 回滚，撤销本事务内刚写入的全部切片。
            throw new StaleIndexException("文档状态已变化，放弃写入");
        }
        return chunks.size();
    }
}
