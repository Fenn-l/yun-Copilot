package com.example.copilot.ticket.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.ticket.dto.AssignTicketRequest;
import com.example.copilot.ticket.dto.CreateTicketRequest;
import com.example.copilot.ticket.dto.TicketActionRequest;
import com.example.copilot.ticket.dto.TicketContentRequest;
import com.example.copilot.ticket.dto.TicketDetailVO;
import com.example.copilot.ticket.dto.TicketVO;
import com.example.copilot.ticket.dto.UpdateTicketAttributesRequest;
import com.example.copilot.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 REST 控制器，统一暴露 /api/tickets 下的工单查询与状态流转端点，所有响应包装为 ApiResponse。
 *
 * <p>鉴权说明：本类方法未标注 @PreAuthorize。身份认证由全局 JWT 无状态过滤器完成，
 * 角色（USER/AGENT/ADMIN）与数据级归属校验统一在 TicketService 落地（requireVisible /
 * requireHandler / 各写方法内的 isAgent、isAdmin 与归属判断）。Controller 只负责参数绑定、
 * @Valid 校验与委托 Service，不直接改状态、不做权限判断。
 *
 * <p>状态流转总览：create 固定初始化 NEW；assign → ASSIGNED；start → IN_PROGRESS；
 * reply 可能触发自动认领 / 自动回到 IN_PROGRESS；wait-user → WAITING_USER；resolve → RESOLVED；
 * close → CLOSED；reopen → IN_PROGRESS；attributes 不改状态。各端点前置条件与事件见方法注释。
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    /** 工单业务服务，状态流转、权限与事件审计的唯一入口。 */
    private final TicketService ticketService;

    /**
     * POST /api/tickets：用户提交新工单。
     * 权限要求：任意已认证用户；状态固定初始化 NEW（禁止前端传 status），写 CREATED 事件；
     * 标题/描述由 @Valid 校验，分类/优先级由 Service 归一化兜底。
     */
    @PostMapping
    public ApiResponse<TicketVO> create(@Valid @RequestBody CreateTicketRequest request) {
        return ApiResponse.ok(ticketService.create(request));
    }

    /**
     * GET /api/tickets/page：分页查询工单列表。
     * 权限与数据级：普通用户强制只看自己的工单（Service 按 userId 过滤）；客服可看全部，
     * mine=true 时只看分配给自己的；另可按状态/处理人/分类/优先级/关键词过滤。只读端点，无状态流转。
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<TicketVO>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Long assigneeId,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String priority,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Boolean mine) {
        return ApiResponse.ok(ticketService.page(page, size, status, assigneeId, category, priority, keyword, mine));
    }

    /**
     * GET /api/tickets/{id}：查询工单详情与事件时间线。
     * 权限与数据级：requireVisible 校验，普通用户仅能看自己的工单，客服可看全部；
     * 返回 TicketDetailVO（工单主体 + 按事件主键升序的时间线）。只读端点，无状态流转。
     */
    @GetMapping("/{id}")
    public ApiResponse<TicketDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.detail(id));
    }

    /**
     * POST /api/tickets/{id}/assign：分配处理人。
     * 权限要求：仅客服/管理员；目标处理人须为 AGENT/ADMIN。
     * 状态前置：NEW 或 ASSIGNED；状态变更：→ ASSIGNED；写 ASSIGNED 事件；
     * 经状态机 assertTransition 校验后走 updateState 乐观锁 CAS，rows==0 抛 409。
     */
    @PostMapping("/{id}/assign")
    public ApiResponse<TicketVO> assign(@PathVariable Long id, @Valid @RequestBody AssignTicketRequest request) {
        return ApiResponse.ok(ticketService.assign(id, request.getAssigneeId()));
    }

    /**
     * POST /api/tickets/{id}/start：开始处理。
     * 权限要求：仅客服；非 ADMIN 时只能处理分配给自己的工单。
     * 状态前置：ASSIGNED（若为 NEW 会先自动认领为 ASSIGNED）；状态变更：→ IN_PROGRESS；
     * 写 ASSIGNED（认领时）+ STARTED 事件；乐观锁 CAS 防并发。
     */
    @PostMapping("/{id}/start")
    public ApiResponse<TicketVO> start(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.start(id));
    }

    /**
     * POST /api/tickets/{id}/reply：回复工单。
     * 权限要求：普通用户只能回复自己的工单；客服只能回复分配给自己的（ADMIN 例外）。
     * 状态前置：CLOSED 不可回复，须先 reopen；客服在 NEW 且未分配时回复自动认领并置 IN_PROGRESS，
     * 在 WAITING_USER 下回复自动回到 IN_PROGRESS；其余情况状态不变。
     * 写 REPLY 事件；涉及状态变更时走乐观锁 CAS。
     */
    @PostMapping("/{id}/reply")
    public ApiResponse<TicketVO> reply(@PathVariable Long id, @Valid @RequestBody TicketContentRequest request) {
        return ApiResponse.ok(ticketService.reply(id, request.getContent()));
    }

    /**
     * POST /api/tickets/{id}/wait-user：标记等待用户补充信息。
     * 权限要求：仅客服（requireHandler，ADMIN 例外）；状态前置：IN_PROGRESS（经状态机校验）；
     * 状态变更：→ WAITING_USER；写 WAITING_USER 事件，content 为空时用默认文案；乐观锁 CAS。
     */
    @PostMapping("/{id}/wait-user")
    public ApiResponse<TicketVO> waitUser(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) TicketActionRequest request) {
        return ApiResponse.ok(ticketService.waitUser(id, request == null ? null : request.getContent()));
    }

    /**
     * POST /api/tickets/{id}/resolve：标记已解决。
     * 权限要求：仅客服（requireHandler，ADMIN 例外）；状态前置：IN_PROGRESS / WAITING_USER；
     * 状态变更：→ RESOLVED 并写 resolvedTime = now()；写 RESOLVED 事件；乐观锁 CAS。
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketVO> resolve(@PathVariable Long id,
                                         @Valid @RequestBody(required = false) TicketActionRequest request) {
        return ApiResponse.ok(ticketService.resolve(id, request == null ? null : request.getContent()));
    }

    /**
     * POST /api/tickets/{id}/close：确认关闭工单。
     * 权限要求：创建人本人，或该工单处理人（requireHandler）；创建人优先放行。
     * 状态前置：RESOLVED（状态机允许）；状态变更：→ CLOSED 并写 closedTime = now()；
     * 写 CLOSED 事件；乐观锁 CAS。
     */
    @PostMapping("/{id}/close")
    public ApiResponse<TicketVO> close(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) TicketActionRequest request) {
        return ApiResponse.ok(ticketService.close(id, request == null ? null : request.getContent()));
    }

    /**
     * POST /api/tickets/{id}/reopen：重新打开工单。
     * 权限要求：创建人本人，或该工单处理人（requireHandler）。
     * 状态前置：RESOLVED / CLOSED；状态变更：→ IN_PROGRESS，清空 resolvedTime 与 closedTime（传 null）；
     * 未分配且操作人是客服时自动把其设为处理人；写 REOPENED 事件；乐观锁 CAS。
     */
    @PostMapping("/{id}/reopen")
    public ApiResponse<TicketVO> reopen(@PathVariable Long id,
                                        @Valid @RequestBody(required = false) TicketActionRequest request) {
        return ApiResponse.ok(ticketService.reopen(id, request == null ? null : request.getContent()));
    }

    /**
     * POST /api/tickets/{id}/attributes：调整分类与优先级。
     * 权限要求：仅客服（ADMIN 例外）；状态前置：非 CLOSED（已关闭须先 reopen）。
     * 状态变更：无（写 UPDATED 事件，oldStatus == newStatus）；分类/优先级转大写并校验合法枚举；
     * 更新带 version 条件做乐观锁，rows==0 抛 409。
     */
    @PostMapping("/{id}/attributes")
    public ApiResponse<TicketVO> attributes(@PathVariable Long id,
                                            @Valid @RequestBody UpdateTicketAttributesRequest request) {
        return ApiResponse.ok(ticketService.updateAttributes(id, request.getCategory(), request.getPriority()));
    }
}
