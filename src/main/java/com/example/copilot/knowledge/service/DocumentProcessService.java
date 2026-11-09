package com.example.copilot.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.ai.gateway.LlmGateway;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.knowledge.chunk.TextChunk;
import com.example.copilot.knowledge.chunk.TextChunker;
import com.example.copilot.knowledge.entity.KbDocument;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.parser.DocumentParserRegistry;
import com.example.copilot.knowledge.parser.ParsedDocument;
import com.example.copilot.knowledge.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 异步文档处理编排器，是 RAG「写链路」的核心：读文件 → 解析 → 切片 → 分批 Embedding → 落库，
 * 并负责文档状态机的推进与失败兜底。
 *
 * <p>在链路中的位置：由 {@link DocumentService#upload} / reprocess 提交任务触发，本身通过
 * {@code @Async("docExecutor")} 在独立线程池运行，与 HTTP 请求线程解耦；
 * 最终调用 {@link DocumentIndexWriter#save} 在短事务内把切片与向量写入 kb_chunk。
 *
 * <p>关键设计（本包含金量最高处）：
 * <ul>
 *   <li>异步：{@link #processAsync} 标注 {@code @Async("docExecutor")}，上传接口提交即返回，不阻塞用户。</li>
 *   <li>幂等与防旧覆盖：进入时先校验 {@code process_version} 与状态，版本不匹配的旧任务直接 return；
 *       再用「带版本条件的 update」把状态抢占为 PROCESSING，{@code rows == 0} 说明被新版本任务接管，当前任务退出。</li>
 *   <li>事务边界：解析、Embedding 属于耗时/远程调用，【全部放在事务外】，避免长事务长期持有数据库连接与锁；
 *       只有最后的 {@code indexWriter.save} 在短事务内落库。这是本项目最重要的性能设计之一。</li>
 *   <li>分批向量化：每 {@link #EMBEDDING_BATCH_SIZE}=10 条切片调用一次 {@code embedBatch}，
 *       控制单次请求体大小与限流风险，并逐批打印进度。</li>
 *   <li>结果校验：向量数量必须严格等于切片数量；以首条向量长度作为统一维度写入文档与切片。</li>
 *   <li>过期结果丢弃：捕获 {@link DocumentIndexWriter.StaleIndexException}（写入前版本/状态被改）时只告警、
 *       不写 FAILED，因为已有更新的任务在跑，旧结果必须直接丢弃。</li>
 *   <li>失败兜底：其它异常统一写 FAILED + error_message（截断 900 字符）+ chunk_count=0，
 *       并通过 {@link #rootMessage} 提取最底层根因，避免把包装异常的无用信息落库。</li>
 *   <li>状态机：UPLOADED → PROCESSING → COMPLETED（成功）/ FAILED（失败）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessService {

    /** 单次 Embedding 请求的切片批量大小，10 条一批，控制请求体与下游限流。 */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /** 文档表 Mapper：读取文档、带版本条件抢占状态、写失败状态。 */
    private final KbDocumentMapper documentMapper;
    /** 解析器注册中心：按 file_type 选择 PDFBox / POI / 文本解析器。 */
    private final DocumentParserRegistry parserRegistry;
    /** 文本切片器：约 420 字符一片、60 字符重叠，并保留页码。 */
    private final TextChunker textChunker;
    /** 本地文件存储：按 file_url 读取文档原始字节。 */
    private final FileStorage fileStorage;
    /** AI 网关：提供当前 Embedding 模型名与批量/单条向量化能力（运行时可切换百炼/Ollama）。 */
    private final LlmGateway llmGateway;
    /** 索引落库器：在事务内二次校验版本后写入切片与向量并推进 COMPLETED。 */
    private final DocumentIndexWriter indexWriter;
    /** 应用配置：切片大小、重叠长度等 RAG 参数。 */
    private final AppProperties properties;

    /**
     * 异步处理入口：把任务投递到 {@code docExecutor} 线程池后即返回，具体逻辑委托给 {@link #process}。
     *
     * <p>设计说明：方法返回 void，处理结果不通过返回值传递，而是写入 kb_document 的状态/切片表，
     * 由前端轮询感知；{@code processVersion} 随任务一同传递，供后续幂等校验使用。
     * 注意 Spring 的 {@code @Async} 需经由代理调用才生效，这里由 {@link DocumentService} 注入本 Service 后调用。
     *
     * @param documentId     待处理文档主键
     * @param processVersion 提交任务时的文档处理版本号
     */
    @Async("docExecutor")
    public void processAsync(Long documentId, int processVersion) {
        process(documentId, processVersion);
    }

    /**
     * 文档处理主流程：校验幂等 → 抢占 PROCESSING →（事务外）解析/切片/分批向量化 →（事务内）落库 → 分类处理异常。
     *
     * <p>这是整个 RAG 写链路的编排中枢，顺序与边界如下：
     * <ol>
     *   <li>查文档；不存在直接返回（可能已被删除）。</li>
     *   <li>版本不匹配返回：说明这是重处理前的旧任务，结果必须作废，防止覆盖新任务。</li>
     *   <li>已 COMPLETED 且 chunk_count &gt; 0 返回：避免对成功文档重复处理。</li>
     *   <li>带版本条件的 update 抢占 PROCESSING（同时清空上次 errorMessage）；rows == 0 说明被新任务接管，退出。</li>
     *   <li>事务外执行：读字节 → 解析 → 有效文本校验 → 切片 → 取当前 Embedding 模型名 → 每 10 条一批向量化
     *       → 校验向量数量与维度 → 调用 {@code indexWriter.save}（短事务）落库。</li>
     *   <li>异常分类：StaleIndexException → 结果过期，仅告警丢弃；其它 → 记 error 并写 FAILED。</li>
     * </ol>
     *
     * @param documentId     待处理文档主键
     * @param processVersion 本任务对应的处理版本号
     */
    public void process(Long documentId, int processVersion) {
        KbDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        // 幂等校验一：版本不匹配说明已有更新的重处理任务，旧任务立即退出，绝不覆盖新结果。
        if (!Objects.equals(document.getProcessVersion(), processVersion)) {
            return;
        }
        // 幂等校验二：已经成功且有切片的文档无需重复处理（应对重复投递/重启后的补偿任务）。
        if ("COMPLETED".equals(document.getStatus()) && document.getChunkCount() != null
                && document.getChunkCount() > 0) {
            return;
        }
        // 带版本条件的抢占式更新把状态置为 PROCESSING：只有版本仍匹配才改得动。
        // rows == 0 表示期间被 reprocess / delete 改过版本，说明新任务已接管，当前任务安静退出。
        int started = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessVersion, processVersion)
                .set(KbDocument::getStatus, "PROCESSING")
                .set(KbDocument::getErrorMessage, null));
        if (started == 0) {
            return;
        }
        long begin = System.currentTimeMillis();
        try {
            // 以下解析与 Embedding 均在事务外：远程调用耗时长，若包在事务里会长时间占用数据库连接。
            byte[] bytes = fileStorage.read(document.getFileUrl());
            // parserRegistry 按 file_type 选解析器：PDF 用 PDFBox 保留页码、DOC/DOCX 用 POI 含表格、
            // 纯文本做 UTF-8→GB18030 编码探测，统一产出带页码的 ParsedDocument。
            ParsedDocument parsed = parserRegistry.parse(bytes, document.getFileType());
            if (parsed.totalChars() < 10) {
                // 有效文本过少，基本可判定为扫描件/空文档/纯图片，直接失败，避免产出无意义向量。
                throw new IllegalStateException("未解析到有效文本（可能是扫描件或空文档）");
            }
            // 约 420 字符一片、60 字符重叠，重叠用于保留跨片语义；页码随片保留以支持「第 N 页」引用。
            List<TextChunk> chunks = textChunker.chunk(parsed,
                    properties.getRag().getChunkSize(), properties.getRag().getChunkOverlap());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文本切片结果为空");
            }
            // 记录本次使用的 Embedding 模型名：切片与文档都会冗余该字段，检索时按「当前模型」过滤，
            // 切换模型后旧向量自然被排除，直到 reprocessAll 重建。
            String embeddingModel = llmGateway.embeddingModelName();
            List<float[]> vectors = new ArrayList<>(chunks.size());
            // 分批向量化：每 EMBEDDING_BATCH_SIZE(10) 条组成一个请求，边界用 [start, end) 半开区间，
            // end 取 min(size, start+10) 保证最后一批不越界；兼顾请求体大小与下游限流。
            for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(chunks.size(), start + EMBEDDING_BATCH_SIZE);
                List<String> texts = chunks.subList(start, end).stream().map(TextChunk::content).toList();
                List<float[]> batch = llmGateway.embedBatch(texts);
                vectors.addAll(batch);
                log.debug("文档 {} 向量化进度 {}/{}", documentId, end, chunks.size());
            }
            // 向量数量必须与切片数量严格一致，否则后续按下标写入会错位甚至越界，直接判失败。
            if (vectors.size() != chunks.size()) {
                throw new IllegalStateException("向量化结果数量与切片数量不一致");
            }
            // 以首条向量长度作为统一维度；同一批次同一模型下维度应一致，检索时据此跳过维度不符的向量。
            int dimension = vectors.get(0).length;
            // 唯一的落库点：indexWriter.save 内部开启短事务，二次校验版本后写切片并推进 COMPLETED。
            int saved = indexWriter.save(documentId, processVersion, chunks, vectors, embeddingModel, dimension);
            log.info("文档处理完成 id={} name={} chunks={} dim={} cost={}ms", documentId,
                    document.getDocumentName(), saved, dimension, System.currentTimeMillis() - begin);
        } catch (DocumentIndexWriter.StaleIndexException e) {
            // 结果过期：解析/向量化期间文档被重处理或删除，已有新任务在跑，旧结果必须直接丢弃；
            // 这里只告警，不写 FAILED，否则会覆盖新任务的进行中状态。
            log.warn("文档处理结果已过期，丢弃 id={} version={} reason={}", documentId, processVersion, e.getMessage());
        } catch (Exception e) {
            // 其它异常统一兜底：记录完整堆栈到日志，但只把根因摘要写入文档状态。
            log.error("文档处理失败 id={} version={}", documentId, processVersion, e);
            markFailed(documentId, processVersion, rootMessage(e));
        }
    }

    /**
     * 把文档标记为 FAILED，并记录失败原因，是异步处理失败时的兜底写状态方法。
     *
     * <p>规则：error_message 截断到 900 字符（防止超长异常堆栈撑爆字段/影响列表展示）；同时把
     * chunk_count 归零，避免前端误以为仍有可用切片。
     *
     * <p>幂等：update 仍带 {@code process_version} 条件——只有版本仍等于本任务的版本才写 FAILED，
     * 从而保证「旧任务的失败」不会覆盖「新任务已写入的 COMPLETED」状态。
     *
     * @param documentId     文档主键
     * @param processVersion 本任务版本号（作为更新条件，实现防旧覆盖）
     * @param message        失败原因（根因摘要），为 null 时记为「未知错误」
     */
    private void markFailed(Long documentId, int processVersion, String message) {
        String error = message == null ? "未知错误" : message;
        // 截断到 900 字符，避免超长堆栈/消息超出 error_message 列长度。
        if (error.length() > 900) {
            error = error.substring(0, 900);
        }
        documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessVersion, processVersion)
                .set(KbDocument::getStatus, "FAILED")
                .set(KbDocument::getErrorMessage, error)
                .set(KbDocument::getChunkCount, 0));
    }

    /**
     * 沿异常链 {@code getCause()} 一路下探到最底层根因，返回其 message 作为落库/日志用的简短原因。
     *
     * <p>为什么需要：Spring / 网关抛出的往往是层层包装的异常（如 CompletionException 包住真正的 HTTP 超时），
     * 直接取最外层 message 通常无业务价值。这里取根因，能让 error_message 直指真正问题。
     *
     * <p>边界：用 {@code getCause() != current} 防止异常链自环导致死循环；根因没有 message 时退化为
     * 其简单类名，保证返回串非空。
     *
     * @param throwable 捕获到的异常
     * @return 根因的 message，或根因类名（无 message 时）
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
