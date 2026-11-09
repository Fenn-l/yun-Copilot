package com.example.copilot.copilot.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.copilot.dto.CopilotResultVO;
import com.example.copilot.copilot.service.CopilotService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服辅助（Copilot）三大建议的 HTTP 入口，路径前缀 /api/copilot/tickets，统一包装为 ApiResponse 返回。
 * <p>
 * 在业务链路中承接客服工作台的点击请求并委派给 CopilotService：只生成建议、不落业务写操作，
 * 工单状态变更与回复发送均由人工在确认后通过各自的业务接口另行完成（核心安全边界）。
 * <ul>
 *   <li>权限：类级鉴权限定仅 AGENT / ADMIN 可调用，普通 USER 无权使用 Copilot，
 *       与全局 JWT 三级角色（USER / AGENT / ADMIN）一致。</li>
 *   <li>方法：三个接口均用 POST，因为每次调用都会触发一次大模型推理并写 cs_ai_run 埋点，
 *       属于「产生一次动作」而非读取既有资源，语义上不适合 GET。</li>
 *   <li>入参：路径中的 id 为工单主键，由 Service 层经 ticketService.detail 校验存在性与可见性。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/copilot/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AGENT','ADMIN')")
public class CopilotController {

    /** 三大 AI 建议场景的编排服务，负责取工单上下文、调模型、写埋点并组装结果。 */
    private final CopilotService copilotService;

    /**
     * 生成工单摘要建议（场景 SUMMARY）：固定 Markdown 五小节，便于客服快速理解工单。
     * 仅返回建议正文，不修改工单；AI 调用成功或失败均会写 cs_ai_run 埋点。
     * @param id 路径中的工单主键
     * @return 统一响应，data 为 type=SUMMARY 的 CopilotResultVO
     */
    @PostMapping("/{id}/summary")
    public ApiResponse<CopilotResultVO> summary(@PathVariable Long id) {
        return ApiResponse.ok(copilotService.summary(id));
    }

    /**
     * 生成工单分类与优先级建议（场景 CLASSIFY）：模型只输出 JSON，后端做枚举白名单校验，
     * 非法值回退 OTHER / MEDIUM 并标记 INVALID；结果须人工确认后回填，绝不自动改分类或状态。
     * @param id 路径中的工单主键
     * @return 统一响应，data 为 type=CLASSIFY 的 CopilotResultVO，含 category / priority / reason
     */
    @PostMapping("/{id}/classify")
    public ApiResponse<CopilotResultVO> classify(@PathVariable Long id) {
        return ApiResponse.ok(copilotService.classify(id));
    }

    /**
     * 生成面向用户的回复草稿建议（场景 REPLY）：先检索知识库 TopK=4、阈值 0.32，
     * 把命中资料编号后拼入 Prompt 以约束模型不编造政策/时限/赔偿，生成 150-300 字草稿并附真实引用；
     * 结果须人工确认后才能发送，绝不自动发送回复。
     * @param id 路径中的工单主键
     * @return 统一响应，data 为 type=REPLY 的 CopilotResultVO，含正文与 citations
     */
    @PostMapping("/{id}/reply-suggestion")
    public ApiResponse<CopilotResultVO> replySuggestion(@PathVariable Long id) {
        return ApiResponse.ok(copilotService.replySuggestion(id));
    }
}
