package com.example.copilot.ticket.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单事件流水实体，对应表 cs_ticket_event，记录工单每一次状态流转与关键操作的审计轨迹。
 *
 * <p>业务链路：TicketService 在建单、分配、开始处理、回复、等待用户、解决、关闭、重开、
 * 属性调整时通过 addEvent 追加一条事件；detail 接口按主键升序读取该工单全部事件，
 * 组装成时间线（TicketDetailVO#events）返回，使处理过程可追溯、可审计。
 * 事件为只追加（append-only）流水，不提供修改与删除。
 *
 * <p>约束：ticketId 为逻辑外键关联 cs_ticket.id；operatorId 关联操作人；
 * oldStatus 为空表示「创建前无状态」，newStatus 与 oldStatus 相同表示「状态未改变」
 * （如普通回复、属性调整）；content 记录回复正文或系统说明文本。
 */
@Data
@TableName("cs_ticket_event")
public class TicketEvent {

    /** 主键，数据库自增（IdType.AUTO）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工单 ID，逻辑外键关联 cs_ticket.id；detail 接口按此字段过滤出时间线。 */
    private Long ticketId;

    /** 操作人 ID，外键关联 system 用户表；toEventVO 会据此回查昵称与角色用于展示。 */
    private Long operatorId;

    /** 事件类型：CREATED（建单）、ASSIGNED（分配/接单）、STARTED（开始处理）、REPLY（回复）、WAITING_USER（等待用户）、RESOLVED（已解决）、CLOSED（关闭）、REOPENED（重开）、UPDATED（属性调整）。 */
    private String eventType;

    /** 变更前状态；建单事件（CREATED）时为 null，表示此前无状态。 */
    private String oldStatus;

    /** 变更后状态；回复、属性调整等不改变状态的事件与 oldStatus 相同。 */
    private String newStatus;

    /** 事件内容：用户/客服回复正文，或系统生成的操作说明（如「分配处理人：XXX」「问题已解决」）。 */
    private String content;

    /** 事件时间，插入时由 FieldFill.INSERT 自动填充，作为时间线排序依据之一。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
