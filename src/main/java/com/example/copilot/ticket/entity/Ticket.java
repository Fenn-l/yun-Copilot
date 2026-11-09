package com.example.copilot.ticket.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单主实体，对应表 cs_ticket，是工单域（ticket）的核心数据模型。
 *
 * <p>业务链路：用户提交建单，或由 AI 会话「转人工」自动建单
 * （见 TicketService#createFromConversation）后，工单进入 NEW 状态等待客服认领；
 * 随后经 ASSIGNED → IN_PROGRESS → WAITING_USER/RESOLVED → CLOSED 的六态状态机
 * （见 TicketStateMachine）流转；每一次状态变更都会同步向 cs_ticket_event 追加事件流水，
 * 由 detail 接口组装成时间线返回。
 *
 * <p>关键设计与约束：
 * <ul>
 *   <li>status 禁止前端直接改写：所有流转须经 TicketStateMachine 校验，再由
 *       TicketMapper#updateState 以「id + version + 原状态」三重条件的 CAS 方式更新，
 *       实现乐观锁；影响行数为 0 即判定为并发冲突并抛 409。</li>
 *   <li>数据级权限：普通用户仅可访问 userId 等于自身的工单；客服仅可处理 assigneeId
 *       等于自身的工单（ADMIN 例外），校验统一在 Service 层完成。</li>
 *   <li>createTime / updateTime 由 MyBatis-Plus 自动填充，业务代码不手动赋值。</li>
 * </ul>
 */
@Data
@TableName("cs_ticket")
public class Ticket {

    /** 主键，数据库自增（IdType.AUTO），仅作内部标识，无业务含义。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单号，对外展示的业务编号，格式 T + yyyyMMddHHmmss + 4 位随机数，由 TicketService#nextTicketNo 生成。 */
    private String ticketNo;

    /** 创建人 ID，外键关联 system 用户表；是普通用户数据级权限的归属判定依据。 */
    private Long userId;

    /** 来源会话 ID，外键关联 conversation 会话表；仅「AI 会话转人工」建单时有值，用户手工建单可为空。 */
    private Long conversationId;

    /** 当前处理人 ID，外键关联 system 用户（角色须为 AGENT/ADMIN）；未分配时为 null。客服仅能处理分配给自己（assigneeId 等于自身）的工单，ADMIN 不受此限。 */
    private Long assigneeId;

    /** 工单标题，用户提交时最长 200 字符；转人工场景取会话标题，超过 200 字符会截断。 */
    private String title;

    /** 问题描述，用户提交时最长 5000 字符；转人工场景由会话最近消息拼成摘要，最长 3000 字符。 */
    private String description;

    /** 分类枚举：TECHNICAL（技术）、ACCOUNT（账号）、AFTER_SALES（售后）、PRODUCT（产品）、OTHER（其他）。未显式指定时按「标题+描述」关键词推断。 */
    private String category;

    /** 优先级枚举：LOW（低）、MEDIUM（中）、HIGH（高）、URGENT（紧急）。命中「紧急/宕机/无法使用」关键词时自动升为 HIGH。 */
    private String priority;

    /** 状态枚举（六态）：NEW（新建）、ASSIGNED（已分配）、IN_PROGRESS（处理中）、WAITING_USER（待用户回复）、RESOLVED（已解决）、CLOSED（已关闭）。禁止前端直接赋值，须经状态机流转。 */
    private String status;

    /** 乐观锁版本号，初始为 0，每次成功更新（updateState / 属性调整）自增 1；更新 SQL 的 WHERE 携带该值做 CAS，防止并发覆盖。 */
    private Integer version;

    /** 解决时间：resolve() 写入 LocalDateTime.now()；reopen() 重开时清空为 null。 */
    private LocalDateTime resolvedTime;

    /** 关闭时间：close() 写入 LocalDateTime.now()；reopen() 重开时清空为 null。 */
    private LocalDateTime closedTime;

    /** 创建时间，插入时由 FieldFill.INSERT 自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时由 FieldFill.INSERT_UPDATE 自动填充；updateState 语句亦显式写 update_time = NOW()。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
