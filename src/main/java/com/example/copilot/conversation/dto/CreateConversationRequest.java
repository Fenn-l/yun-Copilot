package com.example.copilot.conversation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「创建会话」接口的入参 DTO（POST /api/conversations，请求体允许为 null）。
 *
 * <p>title 为可选：为 null 或空白时由 ConversationService.create 回退为默认标题「新的对话」，
 * 并在首次提问时由 updateTitleIfDefault 依据问题内容自动生成标题，因此不强制填写。
 * 由 jakarta.validation 的 Size 约束做长度校验，配合 Controller 的方法参数校验生效。
 */
@Data
public class CreateConversationRequest {

    /**
     * 会话标题，可选，最长 100 字符（Size 约束，超出提示「标题过长」）。留空则用默认标题，首次提问后自动改写。
     */
    @Size(max = 100, message = "标题过长")
    private String title;
}
