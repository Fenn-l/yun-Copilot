package com.example.copilot.conversation.dto;

import com.example.copilot.ai.dto.CitationVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息视图对象，用于「查询会话消息列表」接口的出参（GET /api/conversations/{id}/messages），
 * 统一包装在 ApiResponse 中返回。
 *
 * <p>由 ConversationService.toVO(Message) 组装：与实体最大的差异是 citations——实体以 JSON 字符串
 * 存储，出参反序列化为 {@code List<CitationVO>} 供前端直接渲染引用编号与来源；
 * 解析异常降级为空列表，避免脏数据导致整条消息不可用。handoffRequired 以 Boolean.TRUE.equals 做 null 安全还原。
 */
@Data
@Builder
public class MessageVO {
    /**
     * 消息主键，同一会话内自增，升序即为消息时间顺序。
     */
    private Long id;
    /**
     * 所属会话 ID，关联 cs_conversation.id。
     */
    private Long conversationId;
    /**
     * 消息角色：USER（用户提问）、ASSISTANT（AI 助手或人工客服回复）。
     */
    private String role;
    /**
     * 消息正文。
     */
    private String content;
    /**
     * 消息类型，当前固定为 TEXT。
     */
    private String messageType;
    /**
     * token 估算值，用于成本统计与上下文裁剪。
     */
    private Integer tokenCount;
    /**
     * 引用列表，来自 ai 域 CitationVO；由实体 citations JSON 反序列化得到，无引用或解析失败为空列表。
     * 引用编号已在后端清洗以防幻觉。
     */
    private List<CitationVO> citations;
    /**
     * 是否需转人工：true 表示知识不足或 AI 故障，前端据此引导用户等待人工或工单介入。
     */
    private Boolean handoffRequired;
    /**
     * 转人工原因说明。
     */
    private String handoffReason;
    /**
     * 消息创建时间。
     */
    private LocalDateTime createTime;
}
