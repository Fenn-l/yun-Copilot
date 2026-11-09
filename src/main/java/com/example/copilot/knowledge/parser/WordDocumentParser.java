package com.example.copilot.knowledge.parser;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Word 文档解析器，基于 Apache POI，同时支持新版 .docx 与旧版 .doc。
 *
 * <p>在 RAG 链路中的位置：把 Word 文件抽取为逐页文本供 TextChunker 切片。
 * 两种格式二进制结构完全不同，必须分别处理：.docx 是 OOXML 压缩包用 XWPF，
 * .doc 是 OLE2 复合文档用 HWPF，二者 API 不通用。
 *
 * <p>关键设计：
 * <ul>
 *   <li>.docx(XWPF)：按 body 元素顺序遍历，段落直接取文本，表格逐行逐单元格抽取并用
 *       竖线拼接，保证表格内容不丢失；该格式不暴露真实页码，统一记为第 1 页。</li>
 *   <li>.doc(HWPF)：用 WordExtractor 取全文，再按换页符（form feed）切分为多页，
 *       页码从 1 递增；无文本时兜底一个空页，避免解析结果为空列表。</li>
 * </ul>
 */
@Component
public class WordDocumentParser implements DocumentParser {

    @Override
    /**
     * 支持 docx 与 doc 两种扩展名（小写精确匹配）。
     * <p>两者走不同解析分支，由 parse 按扩展名再分派。
     */
    public boolean supports(String extension) {
        return "docx".equals(extension) || "doc".equals(extension);
    }

    @Override
    /**
     * 按扩展名分派到 docx 或 doc 的解析实现。
     * <p>规则：docx 走 XWPF（parseDocx），其余（doc）走 HWPF（parseDoc）；
     * 之所以在解析方法内二次判断，是因为一个 Bean 同时认领两种扩展名。
     * 边界：异常向上抛出，由异步任务置 FAILED 并写 errorMessage。
     */
    public ParsedDocument parse(byte[] data, String extension) throws Exception {
        if ("docx".equals(extension)) {
            return parseDocx(data);
        }
        return parseDoc(data);
    }

    /**
     * 解析 .docx（OOXML / XWPF）。
     * <p>规则：按 getBodyElements() 的文档顺序遍历，段落取文本、表格逐行逐单元格取文本，
     * 全部追加到同一 builder；表格单元格用竖线拼接，避免结构化内容丢失。
     * 边界：XWPF 不提供可靠物理页码，结果统一封装为第 1 页；空段落跳过，
     * 空文档仍返回一个空文本页，保证下游有可遍历的 pages。
     */
    private ParsedDocument parseDocx(byte[] data) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(data))) {
            // 按正文顺序遍历段落与表格，保持原文次序不颠倒。
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        builder.append(text.trim()).append('\n');
                    }
                // 表格分支：逐行逐单元格抽取，防止表格内容在纯段落抽取中被遗漏。
                } else if (element instanceof XWPFTable table) {
                    // 每行单元格用竖线拼接成一行文本，保留列间边界。
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText() == null ? "" : cell.getText().trim());
                        }
                        builder.append(String.join(" | ", cells)).append('\n');
                    }
                }
            }
        }
        // docx 无真实页码，整体记为第 1 页；页码一致性由切片阶段沿用。
        return new ParsedDocument(List.of(new ParsedPage(1, builder.toString())));
    }

    /**
     * 解析 .doc（OLE2 / HWPF）。
     * <p>规则：用 WordExtractor 取全文，按换页符（form feed）切分成多页，
     * 页码从 1 递增；换页符是旧版 Word 表示分页的约定，可近似还原物理页。
     * 边界：text 为 null 或切分结果为空时，兜底一个第 1 页空文本，
     * 避免 ParsedDocument.pages 为空导致下游无页可切。
     */
    private ParsedDocument parseDoc(byte[] data) throws Exception {
        String text;
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(data));
             WordExtractor extractor = new WordExtractor(document)) {
            text = extractor.getText();
        }
        // 以换页符切分出的每一段作为一页，页码从 1 开始递增。
        List<ParsedPage> pages = new ArrayList<>();
        if (text != null) {
            String[] parts = text.split("\f");
            int pageNo = 1;
            // 每个分页片段封装为 ParsedPage，页码随循环递增。
            for (String part : parts) {
                pages.add(new ParsedPage(pageNo++, part));
            }
        }
        // 空文本兜底：至少产出 1 个空页，保证下游切片流程可正常运行。
        if (pages.isEmpty()) {
            pages.add(new ParsedPage(1, ""));
        }
        return new ParsedDocument(pages);
    }
}
