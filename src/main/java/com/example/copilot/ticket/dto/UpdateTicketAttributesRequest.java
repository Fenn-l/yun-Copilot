package com.example.copilot.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 调整工单分类与优先级的入参，用于 POST /api/tickets/{id}/attributes（attributes 接口）。
 *
 * <p>仅客服可调用（ADMIN 属客服体系例外放行），且已关闭工单不可调整；两个字段均必填，
 * Service 会转大写并校验是否属于 CATEGORIES / PRIORITIES 合法枚举。
 * 更新时同样带 version 条件做乐观锁（rows==0 抛 409），成功后写入 UPDATED 事件。
 */
@Data
public class UpdateTicketAttributesRequest {

    /** 新分类，必填；合法值 TECHNICAL/ACCOUNT/AFTER_SALES/PRODUCT/OTHER，Service 转大写后校验。 */
    @NotBlank(message = "请选择分类")
    private String category;

    /** 新优先级，必填；合法值 LOW/MEDIUM/HIGH/URGENT，Service 转大写后校验。 */
    @NotBlank(message = "请选择优先级")
    private String priority;
}
