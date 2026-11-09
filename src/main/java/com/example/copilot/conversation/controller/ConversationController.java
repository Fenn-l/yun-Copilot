package com.example.copilot.conversation.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.conversation.dto.ConversationVO;
import com.example.copilot.conversation.dto.CreateConversationRequest;
import com.example.copilot.conversation.dto.MessageVO;
import com.example.copilot.conversation.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话域 REST 控制器，挂载在 /api/conversations，对外暴露会话的创建、分页、详情、消息列表与结束五个接口。
 *
 * <p>业务链路位置：处于 Spring Security + JWT 鉴权之后的最外层，本身只做参数接收与响应包装
 * （统一返回 ApiResponse），不含业务逻辑；数据级权限、会话状态校验、上下文窗口等全部下沉到
 * ConversationService。请求进入前已由 JWT 过滤器解析出 CurrentUser（含 USER / AGENT / ADMIN 三级角色）。
 *
 * <ul>
 *   <li>所有涉及单个会话的接口均经 Service 的 requireAccessible 做归属校验：普通用户只能访问
 *       自己的会话，AGENT / ADMIN 可查看工作区会话。</li>
 *   <li>结束会话（close）进一步限制为会话创建人或 ADMIN；CLOSED 后不可再提问。</li>
 *   <li>消息列表按 id 升序返回，支持可选 limit（上限 500）做上下文窗口取数裁剪。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    /**
     * 会话业务服务，由 Lombok 的 RequiredArgsConstructor 构造器注入（final 字段），本控制器仅委托其处理逻辑。
     */
    private final ConversationService conversationService;

    /**
     * 创建会话。请求体允许为空（required = false），title 为空时回退默认标题「新的对话」。
     * 会话号由时间戳加随机数生成，初始状态置为 ACTIVE，userId 取当前 JWT 用户；
     * 事务内插入后返回新建会话的 ConversationVO，外层统一包装为 ApiResponse。
     */
    @PostMapping
    public ApiResponse<ConversationVO> create(@Valid @RequestBody(required = false) CreateConversationRequest request) {
        return ApiResponse.ok(conversationService.create(request == null ? null : request.getTitle()));
    }

    /**
     * 会话分页列表。page 默认 1、size 默认 20，按 updateTime 倒序（最近活跃在前）。
     * 数据级权限：普通用户仅返回自己的会话（Service 内叠加 userId 条件），AGENT / ADMIN 返回工作区全部会话。
     * 返回 PageResult 包装的分页数据（记录、总数、当前页、每页大小）。
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<ConversationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(conversationService.page(page, size));
    }

    /**
     * 会话详情。经 requireAccessible 校验归属：会话不存在抛 404，非本人且非 AGENT / ADMIN 抛 403。
     * 校验通过后返回含 userName、lastMessage 预览的 ConversationVO，避免越权读取他人会话。
     */
    @GetMapping("/{id}")
    public ApiResponse<ConversationVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.detail(id));
    }

    /**
     * 查询会话消息列表。先经 requireAccessible 校验权限，再按 conversation_id 过滤、按 id 升序返回（时间正序）。
     * limit 可选：为空或非正数时取全量，否则取 min(limit, 500) 条，避免一次拉取过多消息撑大响应，
     * 该上限也约束 AI 拼装「最近 N 条上下文窗口」时的取数规模；出参中 citations 由 JSON 还原为列表。
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageVO>> messages(@PathVariable Long id,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(conversationService.messages(id, limit));
    }

    /**
     * 结束会话，将状态置为 CLOSED。权限更严格：仅会话创建人或 ADMIN 可操作（AGENT 亦不可），否则抛 403。
     * CLOSED 后业务上禁止继续提问与追加消息，前端应关闭输入入口；返回更新后的会话 VO。
     */
    @PostMapping("/{id}/close")
    public ApiResponse<ConversationVO> close(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.close(id));
    }
}
