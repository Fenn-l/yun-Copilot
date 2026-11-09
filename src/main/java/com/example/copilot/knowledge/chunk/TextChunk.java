package com.example.copilot.knowledge.chunk;

/**
 * 单个文本切片的数据结构，是解析结果（ParsedDocument）与落库实体（KbChunk）之间的中间载体。
 *
 * <p>在 RAG 链路中的位置：TextChunker 把逐页文本切分后产出 TextChunk 列表，
 * Service 层再逐条转成 KbChunk 并生成向量写入 kb_chunk；index 与 pageNumber 保证
 * 切片顺序与来源页码可追溯，供回答引用展示，content 则是送进大模型上下文的原文片段。
 *
 * @param index      切片序号，从 0 递增，标识在文档中的顺序（含重叠切片仍可还原原文次序）
 * @param pageNumber 来源页码，来自 ParsedPage.pageNumber()，逐页保留以便引用溯源
 * @param content    切片正文文本，约 420 字符且含与相邻切片的 60 字符重叠
 */
public record TextChunk(int index, int pageNumber, String content) {
}
