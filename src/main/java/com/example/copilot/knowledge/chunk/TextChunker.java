package com.example.copilot.knowledge.chunk;

import com.example.copilot.knowledge.parser.ParsedDocument;
import com.example.copilot.knowledge.parser.ParsedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片器，把 ParsedDocument 的逐页文本切成适合检索与大模型上下文的定长片段。
 *
 * <p>在 RAG 链路中的位置：位于「文档解析」与「Embedding / kb_chunk 落库」之间。
 * 输入是解析得到的逐页文本（ParsedPage），输出 TextChunk 列表；切片质量直接决定
 * 余弦相似度 TopK 召回的准确性。项目默认 chunkSize 约 420 字符、overlap 60 字符。
 *
 * <p>关键设计：
 * <ul>
 *   <li>按段落聚合：先按空行切段，再用 buffer 把相邻短段落拼接，直到接近 chunkSize 才落一片，
 *       尽量让一个切片承载完整语义，而不是机械按字数硬切。</li>
 *   <li>长段落按句切：超长段落先按 size 开窗，并优先在句末标点处收口，避免把句子拦腰截断。</li>
 *   <li>60 字符重叠：相邻切片共享尾部 overlap 个字符，使跨边界的实体/句子在两片中都出现，
 *       降低语义被边界切断导致的漏召回。</li>
 *   <li>保留页码：每页独立处理、buffer 每页重置，切片不跨页混合，pageNumber 随切片写入，
 *       供回答引用溯源；因此重叠也只发生在同一页的相邻切片之间，不跨页。</li>
 *   <li>边界保护：chunkSize 下限钳到 120、overlap 上限为 size/2，过滤小于 10 字符的碎片，
 *       防止参数异常导致切片数量爆炸或内容大量重复。</li>
 * </ul>
 */
@Component
public class TextChunker {

    /** 丢弃过短碎片的阈值：小于 10 字符的尾部残留不单独成片，避免噪声切片浪费一次向量调用。 */
    private static final int MIN_CHUNK_CHARS = 10;

    /**
     * 按页遍历文档并生成切片列表，是索引构建的核心入口。
     * <p>规则：逐页 normalize -> splitBlocks 得到段落块 -> 用 buffer 聚合到不超过 size ->
     * 满则 flush 一片并在下一片头部带上 overlap 尾部；每页结尾处理残留并去重。
     * 每个切片携带当前页的 pageNumber，index 全局递增。
     * @param document 解析后的文档（含逐页文本与页码）
     * @param chunkSize 目标切片字符数，内部下限钳到 120
     * @param overlap 相邻切片重叠字符数（默认 60），内部上限钳到 size/2
     * @return 带序号、页码、文本的切片列表；空页不产生切片
     */
    public List<TextChunk> chunk(ParsedDocument document, int chunkSize, int overlap) {
        // 切片大小下限 120：防止调用方传入过小值产生海量切片（切片数量上限保护）。
        int size = Math.max(chunkSize, 120);
        // 重叠默认 60 字符，且不得超过半个切片：过大重叠会让相邻内容大量重复甚至推进失效。
        int overlapSize = Math.max(0, Math.min(overlap, size / 2));
        List<TextChunk> chunks = new ArrayList<>();
        int index = 0;
        // 逐页独立处理：buffer 每页新建，切片不跨页，页码随片保留；重叠只在同一页内发生。
        for (ParsedPage page : document.pages()) {
            String text = normalize(page.text());
            // 空白页（如扫描件无文本层）直接跳过，不产生空切片。
            if (text.isBlank()) {
                continue;
            }
            // 先按空行分段、长段按句切，得到语义相对完整的段落块。
            List<String> blocks = splitBlocks(text, size);
            StringBuilder buffer = new StringBuilder();
            for (String block : blocks) {
                if (block.isBlank()) {
                    continue;
                }
                if (buffer.length() == 0) {
                    buffer.append(block);
                // 边界判断：加上分隔符 1 字符后仍不超过 size 才继续拼接，否则先 flush 当前片。
                } else if (buffer.length() + 1 + block.length() <= size) {
                    buffer.append('\n').append(block);
                } else {
                    // 达到上限，把 buffer 定稿为一个待写入的切片文本。
                    String flushed = buffer.toString().trim();
                    if (flushed.length() >= MIN_CHUNK_CHARS) {
                        // 生成切片：index 全局递增，pageNumber 取当前页，保证顺序与来源页码可追溯。
                        chunks.add(new TextChunk(index++, page.pageNumber(), flushed));
                    }
                    // 重叠窗口：截取上一片尾部 overlap 个字符作为下一片开头，避免边界处语义被切断。
                    String tail = flushed.length() > overlapSize
                            ? flushed.substring(flushed.length() - overlapSize) : flushed;
                    // 清空 buffer，下一片从重叠尾部重新开始，再追加当前放不下的块。
                    buffer.setLength(0);
                    if (!tail.isBlank()) {
                        buffer.append(tail).append('\n');
                    }
                    buffer.append(block);
                }
            }
            // 本页最后一个 buffer 未达上限，作为尾片处理（同样受最小长度阈值约束）。
            String last = buffer.toString().trim();
            if (last.length() >= MIN_CHUNK_CHARS) {
                // 去重：若与上一片内容完全相同（可能由重叠尾部造成）则不重复写入。
                if (chunks.isEmpty() || !chunks.get(chunks.size() - 1).content().equals(last)) {
                    chunks.add(new TextChunk(index++, page.pageNumber(), last));
                }
            }
        }
        return chunks;
    }

    /**
     * 规整解析文本，消除换行与空白差异，为后续分段提供干净输入。
     * <p>规则：null 返回空串；把 CRLF/CR 统一为 LF、不间断空格转为普通空格、
     * 去掉行尾空白、把三个及以上连续换行压成两个（保留段落分界），最后 trim。
     * 边界：仅做等价空白规整，不改动正文内容，避免影响字符数统计与页码语义。
     */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replace('\u00a0', ' ');
        normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    /**
     * 把单页文本切成段落块：按空行分段，过长段落再按句切分。
     * <p>规则：以空行（两个及以上换行）为段落边界；短段落整段作为一个块；
     * 超过 size 的长段落用滑动窗口按 size 切，并尽量在句末标点处收口，
     * 只有当句末位置超过窗口一半时才采用，避免切出过短碎片；块内窗口首尾相接不重叠
     * （重叠由上层 chunk 在相邻切片间统一处理）。
     * @param text 已 normalize 的单页文本
     * @param size 块的最大字符数
     * @return 段落块列表，空白段被丢弃
     */
    private List<String> splitBlocks(String text, int size) {
        List<String> blocks = new ArrayList<>();
        for (String paragraph : text.split("\\n{2,}")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= size) {
                blocks.add(trimmed);
                continue;
            }
            int start = 0;
            while (start < trimmed.length()) {
                // 长段落按 size 开一个窗口，末段不足 size 时取到文本末尾。
                int end = Math.min(trimmed.length(), start + size);
                if (end < trimmed.length()) {
                    // 在窗口内向前找最近的句末标点，优先在完整句子处收口。
                    int sentenceEnd = lastSentenceEnd(trimmed, start, end);
                    // 仅当句末超过窗口一半才采用：太靠前的句末会把块切得过短。
                    if (sentenceEnd > start + size / 2) {
                        end = sentenceEnd;
                    }
                }
                // 产出 [start, end) 区间的块，随后窗口推进到 end，块内不重叠。
                blocks.add(trimmed.substring(start, end).trim());
                start = end;
            }
        }
        return blocks;
    }

    /**
     * 在 [start, end) 窗口内从后向前查找最近的句末标点位置。
     * <p>规则：命中中英文句末标点（句号/叹号/问号/分号/换行）时返回其后一位，
     * 使切点落在句子之后；找不到则返回 end，退化为按字数硬切。
     * 边界：始终返回大于 start 的下标，保证切出的块非空、窗口能向前推进。
     * @return 建议的切分下标（句末后一位或 end）
     */
    private int lastSentenceEnd(String text, int start, int end) {
        // 从窗口末尾反向扫描，优先取最靠近边界的句末，保证切出的块尽量接近 size。
        for (int i = end - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '.' || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return end;
    }
}
