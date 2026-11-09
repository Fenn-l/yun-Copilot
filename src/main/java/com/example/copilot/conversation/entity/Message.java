package com.example.copilot.conversation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体，对应数据库表 cs_message，存储会话中每一轮用户输入或 AI/客服回复。
 *
 * <p>业务链路位置：AI 问答把用户提问与助手回答分别落为一条消息；RAG 命中知识库时把引用列表
 * 序列化进 citations；知识不足或 AI 故障时置 handoffRequired 触发转人工，工单域据此以
 * conversation_id 关联会话并取最近 8 条消息生成工单摘要。
 *
 * <ul>
 *   <li>role 取值：USER（用户提问）、ASSISTANT（AI 助手或人工客服回复）。</li>
 *   <li>conversationId 外键关联 cs_conversation.id，并建 (conversation_id, id) 联合索引，
 *       支撑「按会话 + id 排序取最近 N 条」的上下文窗口查询。</li>
 *   <li>tokenCount 为调用方估算的 token 数，用于成本统计与上下文窗口裁剪，不在本包内计算。</li>
 *   <li>createTime 由 MyBatis-Plus 插入时自动填充，id 自增天然保证同一会话内的时序。</li>
 * </ul>
 */
@Data
@TableName("cs_message")
public class Message {

    /**
     * 主键 ID，数据库自增（IdType.AUTO）。同一会话内按 id 升序即为消息时间顺序，
     * 是 recentMessages 做上下文窗口排序、以及取最后一条消息预览的依据。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属会话 ID，外键关联 cs_conversation.id。与 id 组成联合索引，使「按 conversation_id 过滤、
     * 按 id 排序取最近 N 条」可走索引；转人工生成的工单也通过本会话 ID 与消息链路关联。
     */
    private Long conversationId;

    /**
     * 消息角色，全量取值：USER（用户提问）、ASSISTANT（AI 助手或人工客服回复）。
     * AI 构造上下文窗口时据此区分提问与回答；去重当前提问时通常识别最新一条 USER 消息。
     */
    private String role;

    /**
     * 消息正文（纯文本）。保存用户提问原文或 AI/客服回复内容；会话列表的 lastMessage 预览
     * 即取最后一条消息正文的前 60 字符。
     */
    private String content;

    /**
     * 消息类型，当前落库值固定为 TEXT（文本消息），由 appendMessage 统一写入，为后续富媒体类型预留扩展位。
     */
    private String messageType;

    /**
     * 本条消息 token 估算值，由 AI 问答链路在落库前估算后经 appendMessage 传入，
     * 用于成本统计与 Spring AI 上下文窗口裁剪；实体本身不做估算。
     */
    private Integer tokenCount;

    /**
     * 引用列表的 JSON 字符串，由 {@code List<CitationVO>} 经 ObjectMapper 序列化写入。
     * 仅 ASSISTANT 且命中知识库时有值，引用编号已在后端清洗以防幻觉；
     * 出参 toVO 时再反序列化还原为列表，解析失败降级为空列表避免脏数据阻断接口。
     */
    private String citations;

    /**
     * 转人工标记，true 表示知识不足或 AI 故障需降级人工。工单域据此创建工单，
     * 并通过 conversation_id 关联本会话；出参用 Boolean.TRUE.equals 做 null 安全还原。
     */
    private Boolean handoffRequired;

    /**
     * 转人工原因说明（如知识库无命中、AI 调用异常），随 handoffRequired 一起落库，
     * 便于客服接手时了解上下文，并作为工单摘要的补充依据。
     */
    private String handoffReason;

    /**
     * 创建时间，插入时由 MyBatis-Plus 自动填充（FieldFill.INSERT），与自增 id 共同确定消息时序。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
