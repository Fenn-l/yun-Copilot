package com.example.copilot.ticket.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单视图对象（VO），用于 page 列表、detail 详情，以及 create/assign/start/reply 等所有写操作的返回。
 *
 * <p>由 TicketService#toVO 从 cs_ticket 实体转换而来，并额外回查用户表补齐 userName / assigneeName
 * 展示名，避免前端按 ID 二次查用户；字段与 cs_ticket 基本一一对应，仅供读取，不承载入参校验，
 * 统一包装进 ApiResponse 返回。
 */
@Data
@Builder
public class TicketVO {
    /** 工单主键 ID。 */
    private Long id;
    /** 工单号（对外业务编号）。 */
    private String ticketNo;
    /** 创建人 ID。 */
    private Long userId;
    /** 创建人展示名，Service 回查用户表得到（昵称优先，无则用户名）。 */
    private String userName;
    /** 关联会话 ID，转人工建单时有值。 */
    private Long conversationId;
    /** 当前处理人 ID，未分配时为 null。 */
    private Long assigneeId;
    /** 处理人展示名，未分配或对应用户不存在时为 null。 */
    private String assigneeName;
    /** 工单标题。 */
    private String title;
    /** 问题描述。 */
    private String description;
    /** 分类枚举值：TECHNICAL/ACCOUNT/AFTER_SALES/PRODUCT/OTHER。 */
    private String category;
    /** 优先级枚举值：LOW/MEDIUM/HIGH/URGENT。 */
    private String priority;
    /** 当前状态枚举值（六态之一）。 */
    private String status;
    /** 当前乐观锁版本号，随工单返回，便于前端感知版本变化。 */
    private Integer version;
    /** 解决时间，未解决为 null。 */
    private LocalDateTime resolvedTime;
    /** 关闭时间，未关闭为 null。 */
    private LocalDateTime closedTime;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最近更新时间。 */
    private LocalDateTime updateTime;
}
