package com.example.copilot.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象，用于「创建会话」「会话分页」「会话详情」「结束会话」等接口的出参，
 * 统一包装在 ApiResponse 中返回。
 *
 * <p>由 ConversationService.toVO(Conversation) 组装：在实体字段基础上补齐
 * userName（关联用户表取昵称，无昵称回退用户名）与 lastMessage（最后一条消息正文截断前 60 字符的预览），
 * 不直接暴露实体，便于控制返回字段并做展示层加工；使用 Lombok 的 {@code @Builder} 构造。
 */
@Data
@Builder
public class ConversationVO {
    /**
     * 会话主键，对应 cs_conversation.id。
     */
    private Long id;
    /**
     * 会话业务编号，创建时生成且全局唯一，用于展示与按编号检索。
     */
    private String conversationNo;
    /**
     * 会话创建人用户 ID。
     */
    private Long userId;
    /**
     * 创建人展示名：优先用户昵称，为空回退用户名；由 toVO 关联用户表补齐，实体本身不含此字段。
     */
    private String userName;
    /**
     * 会话标题，首次提问后自动更新，未提问时为默认值「新的对话」。
     */
    private String title;
    /**
     * 会话状态：ACTIVE（进行中）、CLOSED（已结束）。前端据此禁用结束会话的继续提问入口。
     */
    private String status;
    /**
     * 最后一条消息正文预览，超过 60 字符截断并追加省略号；会话无消息时为 null。
     */
    private String lastMessage;
    /**
     * 会话创建时间。
     */
    private LocalDateTime createTime;
    /**
     * 会话更新时间，随每次消息追加刷新，分页列表按此字段倒序。
     */
    private LocalDateTime updateTime;
}
