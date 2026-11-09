package com.example.copilot.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户提交工单的入参，用于 POST /api/tickets（create 接口）。
 *
 * <p>由 @Valid 触发校验：标题、描述必填，分别限制 200 / 5000 字符；category 与 priority 可省略，
 * 默认 OTHER / MEDIUM，Service 仍会结合「标题+描述」关键词做归一化兜底（命中紧急类关键词自动升 HIGH）。
 * conversationId 可选，用于把工单关联到某次会话。
 * 注意本请求不含 status：状态由服务端固定初始化为 NEW，禁止前端指定，以防绕过状态机。
 */
@Data
public class CreateTicketRequest {

    /** 工单标题，必填，最长 200 字符；Service 会 trim 后落库。 */
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 200, message = "标题过长")
    private String title;

    /** 问题描述，必填，最长 5000 字符；Service 会 trim 后落库。 */
    @NotBlank(message = "问题描述不能为空")
    @Size(max = 5000, message = "描述过长")
    private String description;

    /** 分类，可选，默认 OTHER；传入合法枚举（TECHNICAL/ACCOUNT/AFTER_SALES/PRODUCT/OTHER）时以其为准，否则按关键词推断。 */
    private String category = "OTHER";

    /** 优先级，可选，默认 MEDIUM；合法枚举 LOW/MEDIUM/HIGH/URGENT，非法值时由关键词兜底（紧急类升 HIGH）。 */
    private String priority = "MEDIUM";

    /** 可选关联的会话 ID；传入时 Service 会校验当前用户对该会话的访问权限（普通用户只能是会话本人）。 */
    private Long conversationId;
}
