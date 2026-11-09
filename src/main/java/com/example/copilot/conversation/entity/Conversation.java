package com.example.copilot.conversation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体，对应数据库表 cs_conversation，是一次「用户与 AI/客服」完整问答交互的聚合根。
 *
 * <p>业务链路位置：用户首次提问时由 ConversationService.create 创建会话；每条消息通过
 * cs_message.conversation_id 挂到本表；转人工时工单域以 conversation_id 关联本会话并生成工单，
 * 因此本表也是会话域与工单域之间的关联锚点。
 *
 * <ul>
 *   <li>状态仅两态：ACTIVE（进行中，允许继续提问与追加消息）、CLOSED（已结束，禁止继续提问）。</li>
 *   <li>数据级权限依据：userId 标记会话归属，普通用户只能访问自己的会话，AGENT/ADMIN 可访问工作区会话。</li>
 *   <li>conversationNo 为对外业务编号并带唯一索引；userId 建索引以支撑「按用户分页」查询。</li>
 *   <li>createTime / updateTime 由 MyBatis-Plus 自动填充，updateTime 随消息追加被显式刷新，
 *       用于会话列表按最近活跃度倒序。</li>
 * </ul>
 */
@Data
@TableName("cs_conversation")
public class Conversation {

    /**
     * 主键 ID，数据库自增（IdType.AUTO），用于会话的唯一定位。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话业务编号，形如 C + yyyyMMddHHmmss + 4 位随机数，带唯一索引，用于对外展示与按编号精确检索。
     */
    private String conversationNo;

    /**
     * 会话创建人，外键关联系统用户（system 域 User 实体）主键。是数据级权限的核心字段：
     * 普通用户仅能访问 userId 等于自身的会话；建有索引以支撑按用户分页查询。
     */
    private Long userId;

    /**
     * 会话标题。创建时默认「新的对话」，首次提问时由 updateTitleIfDefault 截取问题前 24 字符自动生成
     * （超长追加省略号），因此该字段会随首问被改写；用户已自定义标题则保持不变。
     */
    private String title;

    /**
     * 会话状态，全量取值：ACTIVE（进行中，允许继续提问与追加消息）、CLOSED（已结束，不可继续提问）。
     * CLOSED 由会话创建人或 ADMIN 通过 close 接口置入，问答链路据此拦截结束会话的新提问。
     */
    private String status;

    /**
     * 创建时间，插入时由 MyBatis-Plus 自动填充（FieldFill.INSERT），业务代码不手动赋值。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间，插入与更新时自动填充（FieldFill.INSERT_UPDATE）；每追加一条消息都会被显式刷新，
     * 作为会话分页列表按最近活跃度倒序排列的依据。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
