package com.example.copilot.knowledge.parser;

/**
 * 解析结果中的「一页」，是文档解析层的最小输出单元，也是页码信息的载体。
 *
 * <p>在 RAG 链路中的位置：各 DocumentParser 把文件拆成若干 ParsedPage 组成
 * ParsedDocument；TextChunker 逐页切片时把 pageNumber 原样写入 TextChunk，
 * 最终落到 kb_chunk.page_number，供回答引用溯源。PDF 按物理页、纯文本按字符窗口
 * 分页、Word 按分页符或整体页码，含义统一为「来源位置编号」。
 *
 * @param pageNumber 页码，从 1 开始（PDF 为物理页号，纯文本为逻辑分页号）
 * @param text       该页抽取出的纯文本；解析失败或空白页可为空串
 */
public record ParsedPage(int pageNumber, String text) {
}
