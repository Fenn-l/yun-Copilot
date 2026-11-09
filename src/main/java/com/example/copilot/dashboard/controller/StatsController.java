package com.example.copilot.dashboard.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.dashboard.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营看板（Dashboard）的 HTTP 入口，路径前缀 /api/admin/stats，对外提供看板总览指标。
 * <p>
 * 仅做只读聚合查询并委派给 StatsService，不写任何业务表；返回统一 ApiResponse 包装的
 * 扁平 Map，字段包含工单/会话/知识库规模、AI 调用成功率、耗时、Token 用量及各类分布与趋势。
 * <ul>
 *   <li>权限：类级鉴权限定仅 AGENT / ADMIN 可访问，普通 USER 无权查看运营数据
 *       （路径含 admin 但权限是 AGENT / ADMIN 两种角色，并非仅管理员）。</li>
 *   <li>方法：GET，因为只是读取并聚合既有数据，不产生副作用。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AGENT','ADMIN')")
public class StatsController {

    /** 看板指标聚合服务，负责跨工单/会话/知识库/AI 埋点多数据源统计。 */
    private final StatsService statsService;

    /**
     * 看板总览：一次性返回全部聚合指标（当日计数、近 7 日趋势、状态/分类分布、AI 场景统计、
     * 平均处理耗时、Token 用量、知识库指标与最近工单）。
     * 空数据由 Service 层兜底（如调用数为 0 时成功率返回 null、平均耗时归 0），本层不做加工。
     * @return 统一响应，data 为保持插入顺序的指标 Map
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(statsService.overview());
    }
}
