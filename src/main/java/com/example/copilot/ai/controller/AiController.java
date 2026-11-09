package com.example.copilot.ai.controller;

import com.example.copilot.ai.dto.ChatRequest;
import com.example.copilot.ai.dto.ChatResponseVO;
import com.example.copilot.ai.dto.FeedbackRequest;
import com.example.copilot.ai.dto.HandoffRequest;
import com.example.copilot.ai.entity.AiRun;
import com.example.copilot.ai.mapper.AiRunMapper;
import com.example.copilot.ai.service.AiChatService;
import com.example.copilot.ai.service.AiConfigService;
import com.example.copilot.ai.service.FeedbackService;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.conversation.service.ConversationService;
import com.example.copilot.ticket.dto.TicketVO;
import com.example.copilot.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 面向普通登录用户的 AI 功能端点（/api/ai），统一用 ApiResponse 包装出参。
 *
 * <p>在 RAG 链路中的位置：/chat 是问答主入口，/feedback 收集评价，
 * /handoff 在 AI 降级或用户主动要求时把会话转工单，/runs 查调用审计，
 * /runtime 展示当前非敏感运行时信息。
 *
 * <p>关键设计与约束：
 * <ul>
 * <li>所有写端点用 @Valid 校验入参；</li>
 * <li>数据级鉴权下沉到 Service（requireAccessible）与此处 /runs 的显式校验，
 * Controller 只做编排；</li>
 * <li>/runtime 只返回 provider / chatModel / embeddingModel / embeddingDim，
 * 绝不返回 API Key，Key 脱敏只在管理端 VO 中处理；</li>
 * <li>/handoff 保证 AI 故障时用户仍可直接创建工单，业务流程不被阻塞。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    // 依赖：AiChatService 编排问答，FeedbackService 收评价，AiConfigService 出运行时摘要，
    // TicketService 把会话转工单，ConversationService / AiRunMapper 支撑调用记录鉴权与查询。
    private final AiChatService aiChatService;
    private final FeedbackService feedbackService;
    private final AiConfigService aiConfigService;
    private final TicketService ticketService;
    private final ConversationService conversationService;
    private final AiRunMapper aiRunMapper;

    /**
     * POST /api/ai/chat：RAG 问答主入口。
     *
     * <p>入参 @Valid ChatRequest（conversationId、message、可选 knowledgeBaseId / topK）；
     * 返回 ChatResponseVO：回答、有效引用、handoffRequired、reason、aiRunId、耗时与模型信息。
     * 会话归属与状态校验由 AiChatService 内部完成，异常或降级同样以正常响应返回而非报错。
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponseVO> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(aiChatService.chat(request));
    }

    /**
     * POST /api/ai/feedback：对 AI 回答点赞 / 点踩并附评论。
     * 入参 @Valid FeedbackRequest（messageId、rating、comment），返回空体 ApiResponse；
     * 消息角色与会话归属校验在 FeedbackService 完成。
     */
    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@Valid @RequestBody FeedbackRequest request) {
        feedbackService.submit(request);
        return ApiResponse.ok();
    }

    /**
     * POST /api/ai/handoff：把当前会话转为工单，返回 TicketVO。
     * 入参 @Valid HandoffRequest；用于 AI 降级或用户主动要求人工时兜底，
     * 保证 AI 不可用时用户仍能推进业务，流程不被阻塞。
     */
    @PostMapping("/handoff")
    public ApiResponse<TicketVO> handoff(@Valid @RequestBody HandoffRequest request) {
        return ApiResponse.ok(ticketService.createFromConversation(request));
    }

    /**
     * GET /api/ai/runs/{id}：按 id 查询一次 AI 调用审计记录。
     *
     * <p>权限：记录关联会话时走 requireAccessible 做归属校验；
     * 仅关联工单（无会话）的记录只有 canManageAll 的管理员可看，否则 forbidden；
     * 记录不存在返回 404。用于前端排查回答来源、耗时与失败原因。
     */
    @GetMapping("/runs/{id}")
    public ApiResponse<AiRun> run(@PathVariable Long id) {
        // 先按主键查记录，不存在直接 404。
        AiRun run = aiRunMapper.selectById(id);
        // 不存在：抛 notFound，由全局异常处理转为 404 响应。
        if (run == null) {
            throw BusinessException.notFound("调用记录不存在");
        }
        // 有会话关联：用会话归属做数据级鉴权，普通用户只能看自己会话的调用记录。
        if (run.getConversationId() != null) {
            conversationService.requireAccessible(run.getConversationId());
        // 仅工单关联（无会话）：没有会话维度可校验，收敛为仅管理员可查看。
        } else if (!CurrentUser.canManageAll()) {
            throw BusinessException.forbidden("无权查看该调用记录");
        }
        return ApiResponse.ok(run);
    }

    /**
     * GET /api/ai/runtime：返回当前 AI 运行时的非敏感摘要。
     *
     * <p>出参仅 provider / chatModel / embeddingModel / embeddingDim，
     * 不含 API Key（Key 脱敏仅出现在管理端 AiSettingVO）；普通登录用户即可查询，
     * 供前端展示当前模型能力，不暴露任何凭据与连接地址。
     */
    @GetMapping("/runtime")
    public ApiResponse<Map<String, Object>> runtime() {
        return ApiResponse.ok(aiConfigService.runtime());
    }
}
