package com.example.copilot.ticket.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 状态类操作的可选入参，供 POST /api/tickets/{id}/wait-user、/resolve、/close、/reopen 共用。
 *
 * <p>通过 @RequestBody(required = false) 接收，允许整个请求体缺省；content 为空时
 * TicketService 会填入对应操作的默认说明文案（如「等待用户补充信息」「问题已解决」）。
 * 仅含一个可空字段，因此只约束长度（最长 5000 字符），不做非空校验。
 */
@Data
public class TicketActionRequest {

    /** 操作备注/说明，可为空，最长 5000 字符；为空时由 Service 使用各操作的默认文案。 */
    @Size(max = 5000, message = "内容过长")
    private String content;
}
