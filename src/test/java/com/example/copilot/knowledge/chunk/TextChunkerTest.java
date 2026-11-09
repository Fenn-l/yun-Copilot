package com.example.copilot.knowledge.chunk;

import com.example.copilot.knowledge.parser.ParsedDocument;
import com.example.copilot.knowledge.parser.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void splitsLongDocumentIntoChunks() {
        String text = ("公司员工请假管理制度。员工请假需要提前向部门主管提交申请。" +
                "请假超过三天，需要经过部门经理审批。病假需要提供相关证明材料。").repeat(12);
        ParsedDocument document = new ParsedDocument(List.of(new ParsedPage(1, text)));
        List<TextChunk> chunks = chunker.chunk(document, 200, 40);
        assertTrue(chunks.size() > 2, "长文本应被切分为多个片段");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            assertEquals(i, chunk.index(), "切片序号应连续");
            assertEquals(1, chunk.pageNumber());
            assertFalse(chunk.content().isBlank());
            assertTrue(chunk.content().length() <= 260, "切片长度应接近目标大小");
        }
    }

    @Test
    void keepsPageNumbers() {
        ParsedDocument document = new ParsedDocument(List.of(
                new ParsedPage(1, "第一页内容，介绍邮箱登录失败的排查步骤与常见错误码。"),
                new ParsedPage(2, "第二页内容，介绍邮箱密码重置与管理员审批流程。")));
        List<TextChunk> chunks = chunker.chunk(document, 100, 10);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageNumber());
        assertEquals(2, chunks.get(1).pageNumber());
    }

    @Test
    void ignoresBlankPages() {
        ParsedDocument document = new ParsedDocument(List.of(
                new ParsedPage(1, "   "),
                new ParsedPage(2, "有效内容用于测试空白页过滤逻辑。")));
        List<TextChunk> chunks = chunker.chunk(document, 100, 10);
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).pageNumber());
    }
}
