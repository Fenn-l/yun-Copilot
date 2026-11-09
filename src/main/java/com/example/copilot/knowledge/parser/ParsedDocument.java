package com.example.copilot.knowledge.parser;

import java.util.List;

/**
 * 一份文档的解析结果，聚合若干 ParsedPage，是解析层与切片层之间的数据契约。
 *
 * <p>在 RAG 链路中的位置：DocumentParser 产出 ParsedDocument，TextChunker 逐页
 * 遍历 pages() 切片；plainText() 可拼出全文供预览/兜底，totalChars() 统计总字符数
 * 供处理进度与切片规模评估。页码信息随每页保留，不在此聚合丢失。
 *
 * @param pages 逐页解析结果列表，按页码顺序排列；可为空列表但不可为 null
 */
public record ParsedDocument(List<ParsedPage> pages) {

    /**
     * 把所有页文本按页顺序拼成单一字符串（页间以换行分隔）。
     * <p>规则：跳过 null 与空白页，避免多余空行；用于全文预览、日志或需要整篇文本的场景。
     * 边界：拼接结果不带页码，引用溯源仍应走 pages() 逐页取 pageNumber。
     */
    public String plainText() {
        StringBuilder builder = new StringBuilder();
        // 按页顺序拼接，跳过空页，页与页之间用换行分隔。
        for (ParsedPage page : pages) {
            if (page.text() != null && !page.text().isBlank()) {
                builder.append(page.text()).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 统计所有页文本的总字符数。
     * <p>规则：逐页取 text().length() 求和，null 页按 0 计；反映文档有效文本规模，
     * 可用于预估切片数量与展示处理进度。边界：不做 trim，按原始抽取长度统计。
     */
    public int totalChars() {
        // null 安全的逐页长度求和，得到全文档字符总数。
        return pages.stream().mapToInt(page -> page.text() == null ? 0 : page.text().length()).sum();
    }
}
