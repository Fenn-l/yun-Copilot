package com.example.copilot.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工单详情响应体，用于 GET /api/tickets/{id}（detail 接口）。
 *
 * <p>聚合两类数据：工单主体 ticket（TicketVO）与按时间升序排列的事件时间线 events
 * （List<TicketEventVO>），一次性返回工单全貌与完整处理轨迹，供详情页渲染。
 * 使用 @NoArgsConstructor + @AllArgsConstructor 便于 Jackson 处理与 Service 直接 new 构造。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketDetailVO {
    /** 工单主体信息。 */
    private TicketVO ticket;
    /** 事件时间线，按事件主键升序，含建单到当前的全部流转与回复记录。 */
    private List<TicketEventVO> events;
}
