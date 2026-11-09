package com.example.copilot.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单回复入参，用于 POST /api/tickets/{id}/reply（reply 接口）。
 * 回复为普通用户与客服共用的操作，内容必填且最长 5000 字符；权限、状态前置、
 * 客服自动认领/自动回到处理中等规则均在 TicketService#reply 中处理，本 DTO 只负责内容校验。
 */
@Data
public class TicketContentRequest {

    /** 回复内容，必填，最长 5000 字符；Service 会 trim 后作为 REPLY 事件内容写入时间线。 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 5000, message = "内容过长")
    private String content;
}
