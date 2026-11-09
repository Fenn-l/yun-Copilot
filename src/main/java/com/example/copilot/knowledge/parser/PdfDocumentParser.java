package com.example.copilot.knowledge.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器，基于 PDFBox 逐页抽取文本，是 DocumentParser 的 PDF 实现。
 *
 * <p>在 RAG 链路中的位置：处理上传的 PDF（≤50MB），把每个物理页抽取为一个
 * ParsedPage，页码即 PDF 页序号；后续切片在页内进行，回答可精确引用到「第几页」。
 *
 * <p>关键设计：
 * <ul>
 *   <li>逐页抽取：用 PDFTextStripper 每次只设一页的起止页，避免一次性取全文后
 *       丢失页码归属，从而让 kb_chunk.page_number 可精确溯源。</li>
 *   <li>单页容错：某一页抽取抛异常时降级为空文本而非中断整份文档，
 *       坏页不阻塞其余页进入索引；空白页在切片阶段被跳过。</li>
 *   <li>资源安全：PDDocument 用 try-with-resources 关闭，避免大文件累积句柄。</li>
 * </ul>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    /**
     * 仅支持 pdf 扩展名（小写精确匹配）。
     */
    @Override
    public boolean supports(String extension) {
        return "pdf".equals(extension);
    }

    /**
     * 加载 PDF 并逐页抽取文本。
     * <p>规则：Loader.loadPDF 从字节加载，getNumberOfPages 得总页数，
     * 循环内把 stripper 的起止页都设为当前页号后取文本，保证「一页一 ParsedPage」；
     * 单页异常置空文本继续。边界：空文件或加密损坏文件由加载阶段抛异常，
     * 交上层置 FAILED；扫描件无文本层时各页为空串。
     */
    @Override
    public ParsedDocument parse(byte[] data, String extension) throws Exception {
        List<ParsedPage> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = document.getNumberOfPages();
            // 逐页循环：页码从 1 开始，与 PDF 物理页号一致。
            for (int i = 1; i <= total; i++) {
                // 起止页都设为当前页，确保本次只抽取第 i 页、页码不串页。
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text;
                try {
                    text = stripper.getText(document);
                // 单页抽取失败降级为空文本，坏页不阻塞整份文档入库。
                } catch (Exception e) {
                    text = "";
                }
                // 页码 i 随页文本一起保存，最终写入 kb_chunk.page_number。
                pages.add(new ParsedPage(i, text));
            }
        }
        return new ParsedDocument(pages);
    }
}
