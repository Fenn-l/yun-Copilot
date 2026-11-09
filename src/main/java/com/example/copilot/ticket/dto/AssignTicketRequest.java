package com.example.copilot.ticket.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分配工单入参，用于 POST /api/tickets/{id}/assign（assign 接口）。
 * 仅客服/管理员可调用；目标用户必须是 AGENT 或 ADMIN 角色，且工单状态须为 NEW 或 ASSIGNED，
 * 具体由 TicketService#assign 校验。分配成功后状态置 ASSIGNED 并写 ASSIGNED 事件。
 */
@Data
public class AssignTicketRequest {

    /** 目标处理人 ID，必填（@NotNull）；Service 校验其角色为 AGENT/ADMIN，非法则拒绝分配。 */
    @NotNull(message = "请选择处理人")
    private Long assigneeId;
}
