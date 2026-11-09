package com.example.copilot.knowledge.parser;

/**
 * 文档解析器统一接口，定义「按扩展名声明支持范围」与「把字节流解析为逐页文本」两项能力。
 *
 * <p>在 RAG 链路中的位置：异步解析任务从 FileStorage 读回文件字节后，由
 * DocumentParserRegistry 依据扩展名选中一个实现，调用 parse 得到 ParsedDocument，
 * 再交给 TextChunker 切片。PDF（PDFBox）、Word（POI）、纯文本各有实现，
 * 新增格式只需新增一个 Bean，无需改动调用方（开闭原则）。
 *
 * <p>约束：supports 与 parse 的 extension 均为小写、不含点号（由 Registry 归一化）；
 * 解析过程允许抛出 Exception，由上层任务捕获并置文档状态为 FAILED、写入 errorMessage。
 */
public interface DocumentParser {

    /**
     * 声明本解析器是否处理该扩展名。
     * <p>规则：入参为已归一化的小写扩展名（如 pdf / docx / txt），用精确匹配；
     * Registry 遍历所有实现，第一个返回 true 的即被选中。
     * @param extension 小写扩展名，不含点号
     * @return 是否支持
     */
    boolean supports(String extension);

    /**
     * 把文件字节解析为逐页文本结构。
     * <p>规则：按格式逐页抽取文本并保留页码（PDF 逐页、纯文本按字符窗口分页、
     * Word 按 XWPF/HWPF 差异处理）；表格文本需一并抽取，供后续切片与检索。
     * 边界：data 为完整文件字节（受 50MB 上传上限约束）；解析异常向上抛出，
     * 由异步任务统一转为 FAILED 状态，不在解析器内部吞掉。
     * @param data 文件原始字节
     * @param extension 小写扩展名，用于少数实现的分支（如 docx/doc）
     * @return 含页码与文本的 ParsedDocument
     * @throws Exception 解析失败（文件损坏、加密、编码异常等）
     */
    ParsedDocument parse(byte[] data, String extension) throws Exception;
}
