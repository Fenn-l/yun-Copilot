package com.example.copilot.knowledge.parser;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本类文件解析器，处理 txt / md / markdown / csv / log。
 *
 * <p>在 RAG 链路中的位置：把无内建页码的文本按固定字符窗口切成「逻辑页」，
 * 使后续 TextChunker 仍能逐页切片、保留 pageNumber，与 PDF 的页码语义保持一致。
 *
 * <p>关键设计：
 * <ul>
 *   <li>编码探测：先用严格 UTF-8 解码（非法字节 REPORT 报错），失败再回退 GB18030，
 *       兼容国内常见的 GBK/GB18030 中文文本，避免乱码进入向量。</li>
 *   <li>逻辑分页：PAGE_SIZE = 3000 字符一页，超长文本按窗口切分并递增页码，
 *       避免单页文本过大不利于切片与引用定位。</li>
 * </ul>
 */
@Component
public class TextDocumentParser implements DocumentParser {

    /** 逻辑分页大小：每 3000 字符算一页，为无物理页码的纯文本提供可引用的页码粒度。 */
    private static final int PAGE_SIZE = 3000;

    /**
     * 支持 txt / md / markdown / csv / log（小写精确匹配）。
     * <p>这些格式本质都是纯文本，共用同一套编码探测与逻辑分页逻辑。
     */
    @Override
    public boolean supports(String extension) {
        return "txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension)
                || "csv".equals(extension) || "log".equals(extension);
    }

    /**
     * 解码文本并按 PAGE_SIZE 切成逻辑页。
     * <p>规则：整段不足一页时记为第 1 页；超长时按 3000 字符窗口逐段成页，
     * 页码从 1 递增，末段取到文本末尾。边界：decode 至少返回空串（不会是 null），
     * 空白文本仍产出 1 个空页，由切片阶段跳过。
     */
    @Override
    public ParsedDocument parse(byte[] data, String extension) {
        String text = decode(data);
        List<ParsedPage> pages = new ArrayList<>();
        if (text.length() <= PAGE_SIZE) {
            pages.add(new ParsedPage(1, text));
        } else {
            int pageNo = 1;
            // 按 3000 字符窗口推进，每个窗口作为一个逻辑页，页码递增。
            for (int i = 0; i < text.length(); i += PAGE_SIZE) {
                // 末段用 min 收口到文本末尾避免越界；pageNo 随窗口递增。
                pages.add(new ParsedPage(pageNo++, text.substring(i, Math.min(text.length(), i + PAGE_SIZE))));
            }
        }
        return new ParsedDocument(pages);
    }

    /**
     * 探测字节编码并解码为字符串：UTF-8 优先，失败回退 GB18030。
     * <p>规则：用 UTF-8 decoder 且对畸形/不可映射字节采取 REPORT（抛
     * CharacterCodingException），而不是默认替换或静默跳过，才能准确判断「这不是 UTF-8」；
     * 捕获异常后用 GB18030 重新解码（覆盖 GBK/GB2312，字节序列基本总能解码）。
     * 边界：不做 BOM 判断，纯按字节合法性回退；合法 UTF-8 中文不会误入回退分支。
     */
    private String decode(byte[] data) {
        try {
            // 严格 UTF-8：畸形或不可映射字节直接报错，便于据此触发编码回退。
            return StandardCharsets.UTF_8.newDecoder()
                    // REPORT 而非 REPLACE：一旦不是合法 UTF-8 就抛异常进入 GB18030 分支。
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        // UTF-8 解码失败即判定为 GBK 系编码，回退 GB18030 保证中文不乱码。
        } catch (CharacterCodingException e) {
            // GB18030 兼容 GBK/GB2312，覆盖国内常见中文文本编码。
            return new String(data, Charset.forName("GB18030"));
        }
    }
}
