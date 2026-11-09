package com.example.copilot.ticket.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单事件视图对象，是 detail 接口返回的时间线（TicketDetailVO#events）中的单条记录。
 *
 * <p>由 TicketService#toEventVO 从 cs_ticket_event 转换，并回查用户表补齐 operatorName /
 * operatorRole，用于在前端时间线上展示「谁、以什么身份、做了什么、状态如何变化」。
 * 多条事件按主键升序排列，构成可追溯的审计轨迹。
 */
@Data
@Builder
public class TicketEventVO {
    /** 事件主键 ID，亦为时间线排序依据。 */
    private Long id;
    /** 所属工单 ID。 */
    private Long ticketId;
    /** 操作人 ID。 */
    private Long operatorId;
    /** 操作人展示名，Service 回查用户表得到。 */
    private String operatorName;
    /** 操作人角色（USER/AGENT/ADMIN），回查用户表得到，用于时间线上区分身份。 */
    private String operatorRole;
    /** 事件类型：CREATED/ASSIGNED/STARTED/REPLY/WAITING_USER/RESOLVED/CLOSED/REOPENED/UPDATED。 */
    private String eventType;
    /** 变更前状态，建单事件（CREATED）为 null。 */
    private String oldStatus;
    /** 变更后状态；回复、属性调整等不改状态的事件与 oldStatus 相同。 */
    private String newStatus;
    /** 事件内容（回复正文或系统操作说明）。 */
    private String content;
    /** 事件发生时间，插入时自动填充。 */
    private LocalDateTime createTime;
}
