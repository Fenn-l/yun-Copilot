package com.example.copilot.knowledge.retrieval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.ai.gateway.LlmGateway;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.knowledge.embedding.EmbeddingCodec;
import com.example.copilot.knowledge.entity.KbChunk;
import com.example.copilot.knowledge.entity.KbDocument;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import com.example.copilot.knowledge.mapper.KbChunkMapper;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 向量检索器，是 RAG「读链路」的核心：把用户问题向量化后，在受限候选切片上做余弦相似度打分，返回 TopK 命中。
 *
 * <p>检索流水线（顺序即设计）：
 * <ol>
 *   <li>先经 {@link KnowledgeBaseService#visible} 算出可见知识库 ID 列表，可再按指定 knowledgeBaseId 收窄；</li>
 *   <li>候选切片 SQL 过滤：{@code knowledge_base_id IN 可见集合} + {@code embedding_model = 当前模型}
 *       + {@code embedding IS NOT NULL}，并 {@code LIMIT 20000} 兜底；</li>
 *   <li>再按文档 {@code status = COMPLETED} 过滤（处理中/失败的文档不对外提供证据）；</li>
 *   <li>问题向量化（用与入库相同的当前 Embedding 模型，保证同一向量空间）；</li>
 *   <li>逐条余弦相似度打分 → 分数阈值过滤 → 按分数倒序 → TopK 截断，组装 {@link RetrievalHit}。</li>
 * </ol>
 *
 * <p>为什么「权限」与「模型一致性」必须在打分之前过滤：
 * <ul>
 *   <li>权限：越权切片绝不能进入候选集，更不能靠 Prompt 让模型「自觉忽略」；把 visible 下推成 SQL 的
 *       IN 条件，既从源头守住数据安全，又减少需要解码/打分的向量数量。</li>
 *   <li>模型一致性：不同 Embedding 模型产出的向量不在同一语义空间，彼此的余弦相似度没有意义；
 *       混用会污染排序。运行时切换模型后，旧模型向量会被 {@code embedding_model} 条件自然淘汰，
 *       直到管理员执行 reprocessAll 用新模型重建索引。</li>
 * </ul>
 *
 * <p>小规模暴力检索的取舍：当前把 float32 向量以 BLOB 存在 MySQL，检索时在应用层把候选全部反序列化并
 * 做 O(N·D) 暴力余弦，实现简单、无需额外中间件，适合中小知识库。但候选上限 20000、每次都要解码全部
 * 向量，数据量大时 CPU/内存与延迟线性增长；生产环境应改用专用向量库（pgvector / Qdrant / Milvus）配合
 * ANN 索引（HNSW / IVF）来近似最近邻检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    /** 单次检索最多载入的候选切片数，防止把全表 BLOB 一次性读进内存导致 OOM。 */
    private static final int MAX_CANDIDATES = 20000;

    /** 切片表 Mapper：按知识库/模型过滤候选并取回向量 BLOB。 */
    private final KbChunkMapper chunkMapper;
    /** 文档表 Mapper：批量取回文档并按 COMPLETED 过滤、补充文档名/类型。 */
    private final KbDocumentMapper documentMapper;
    /** 知识库服务：计算可见知识库集合，是权限前置过滤的来源。 */
    private final KnowledgeBaseService knowledgeBaseService;
    /** AI 网关：取当前 Embedding 模型名、把问题向量化（运行时可切换百炼/Ollama）。 */
    private final LlmGateway llmGateway;
    /** 向量编解码器：把 kb_chunk.embedding 的 float32 BLOB 还原成 float[]。 */
    private final EmbeddingCodec embeddingCodec;
    /** 应用配置：默认 TopK 与分数阈值等 RAG 参数。 */
    private final AppProperties properties;

    /**
     * 简化检索入口：不指定知识库，TopK 与阈值均走配置默认值。
     *
     * @param question  用户问题
     * @param principal 当前登录主体，用于计算可见知识库
     * @return 按相关度排序的命中列表
     */
    public List<RetrievalHit> retrieve(String question, UserPrincipal principal) {
        return retrieve(question, principal, null, null, null);
    }

    /**
     * 完整检索入口：按「可见知识库 → 候选过滤 → 文档状态过滤 → 打分 → 阈值 → TopK」流水线返回命中。
     *
     * <p>参数与边界：
     * <ul>
     *   <li>question 为空/空白直接返回空列表，不做向量化。</li>
     *   <li>knowledgeBaseId 非空时只在【可见集合内】收窄；若指定的库不可见，收窄后集合为空，返回空列表，
     *       杜绝越权检索。</li>
     *   <li>topK / threshold 为 null 时分别取配置默认值（默认 TopK=5、阈值=0.34）；最终 limit 用
     *       {@code Math.max(1, limit)} 保证至少返回 1 条并兼容非正入参。</li>
     *   <li>向量维度与问题向量不一致的切片直接跳过，避免不同模型/维度向量误算相似度。</li>
     * </ul>
     *
     * @param question        用户问题
     * @param principal       当前登录主体
     * @param knowledgeBaseId 可选，限定检索的知识库（必须可见）
     * @param topK            可选，返回条数上限；为空取配置默认
     * @param threshold       可选，分数阈值下限；为空取配置默认
     * @return 按 score 倒序、最多 limit 条的 {@link RetrievalHit} 列表；无命中返回空列表
     */
    public List<RetrievalHit> retrieve(String question, UserPrincipal principal, Long knowledgeBaseId,
                                       Integer topK, Double threshold) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        // 权限前置：先拿可见知识库，后续所有候选都受它约束，而不是打分后再剔除。
        List<KnowledgeBase> visible = knowledgeBaseService.visible(principal);
        if (knowledgeBaseId != null) {
            // 只在可见集合内收窄，传入不可见 ID 时结果为空，等于拒绝越权检索。
            visible = visible.stream().filter(kb -> kb.getId().equals(knowledgeBaseId)).toList();
        }
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Long> visibleIds = visible.stream().map(KnowledgeBase::getId).toList();
        // 只用「当前 Embedding 模型」的向量：模型不一致则向量空间不同，相似度无意义。
        String embeddingModel = llmGateway.embeddingModelName();
        // 候选过滤下推到 SQL：可见库 + 当前模型 + embedding 非空；LIMIT 20000 防止全表 BLOB 打爆内存。
        List<KbChunk> candidates = chunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                .in(KbChunk::getKnowledgeBaseId, visibleIds)
                .eq(KbChunk::getEmbeddingModel, embeddingModel)
                .isNotNull(KbChunk::getEmbedding)
                .last("LIMIT " + MAX_CANDIDATES));
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 汇总候选涉及的文档，批量回表后仅保留 COMPLETED 的：处理中/失败的文档不提供证据。
        Set<Long> documentIds = candidates.stream().map(KbChunk::getDocumentId).collect(Collectors.toSet());
        Map<Long, KbDocument> documentMap = documentMapper.selectBatchIds(documentIds).stream()
                .filter(doc -> "COMPLETED".equals(doc.getStatus()))
                .collect(Collectors.toMap(KbDocument::getId, Function.identity()));
        if (documentMap.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeBase> kbMap = visible.stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, Function.identity(), (a, b) -> a));

        // 问题向量化：与切片入库用的是同一个当前模型，保证二者处于同一向量空间可比。
        float[] queryVector = llmGateway.embed(question);
        int limit = topK == null ? properties.getRag().getTopK() : topK;
        double minScore = threshold == null ? properties.getRag().getScoreThreshold() : threshold;

        List<RetrievalHit> scored = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (KbChunk chunk : candidates) {
            KbDocument document = documentMap.get(chunk.getDocumentId());
            if (document == null) {
                // 文档非 COMPLETED（或已不存在），跳过该切片。
                continue;
            }
            float[] vector = embeddingCodec.fromBytes(chunk.getEmbedding());
            if (vector.length != queryVector.length) {
                // 维度不一致（可能来自旧模型/异常数据），跳过，避免余弦公式越界或算出无意义分数。
                continue;
            }
            double score = cosine(queryVector, vector);
            if (score < minScore) {
                // 分数低于阈值（默认 0.34）视为不相关，过滤掉，减少噪声进入 Prompt。
                continue;
            }
            if (!seen.add(chunk.getId())) {
                // 按 chunk_id 去重，防止同一切片被重复计入。
                continue;
            }
            KnowledgeBase kb = kbMap.get(chunk.getKnowledgeBaseId());
            scored.add(RetrievalHit.builder()
                    .chunkId(chunk.getId())
                    .documentId(chunk.getDocumentId())
                    .documentName(document.getDocumentName())
                    .fileType(document.getFileType())
                    .knowledgeBaseId(chunk.getKnowledgeBaseId())
                    .knowledgeBaseName(kb == null ? null : kb.getName())
                    .chunkIndex(chunk.getChunkIndex())
                    .pageNumber(chunk.getPageNumber())
                    .content(chunk.getContent())
                    .score(score)
                    .build());
        }
        // 按分数倒序后取 TopK；Math.max(1, limit) 保证至少 1 条并兜底非正 topK。
        return scored.stream()
                .sorted(Comparator.comparingDouble(RetrievalHit::getScore).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 计算两个等长向量的余弦相似度：{@code cos = (a·b) / (|a|·|b|)}。
     *
     * <p>实现：一次循环累加点积 dot 与两个模长平方 normA / normB（用 double 累乘降低 float 精度损失）；
     * 任一向量为零向量（模长为 0）时返回 0，避免除零。调用方已保证 a、b 长度相等。
     * 取值范围 [-1, 1]，值越大表示语义越相近。
     *
     * @param a 向量一（通常为问题向量）
     * @param b 向量二（切片向量）
     * @return 余弦相似度得分
     */
    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
