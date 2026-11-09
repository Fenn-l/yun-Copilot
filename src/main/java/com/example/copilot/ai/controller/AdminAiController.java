package com.example.copilot.ai.controller;

import com.example.copilot.ai.dto.AiSettingVO;
import com.example.copilot.ai.dto.UpdateAiSettingRequest;
import com.example.copilot.ai.service.AiConfigService;
import com.example.copilot.common.result.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 配置管理端点（/api/admin/ai-setting），仅管理员可访问。
 *
 * <p>在 RAG 链路中的位置：维护 AiConfigService 管理的单行运行时配置，
 * 决定 AiChatService 使用哪个提供方、模型与 Embedding 配置；
 * 配置变更经网关清缓存后立即影响后续问答与检索。
 *
 * <p>关键设计与约束：
 * <ul>
 * <li>类级 @PreAuthorize 要求 ADMIN 角色，统一拦截整组端点，权限仅 ADMIN，
 * Service 层不重复鉴权；</li>
 * <li>出参 AiSettingVO 的 Key 为脱敏值，接口不返回明文凭据；</li>
 * <li>update 会触发网关清缓存；切换 Embedding 模型或维度后还需重建向量索引；</li>
 * <li>test 仅探测连通性，不落库、不改动当前配置。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/ai-setting")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiController {

    // 仅依赖 AiConfigService 完成配置查看、更新与连接测试，权限由类级注解保证。
    private final AiConfigService aiConfigService;

    /**
     * GET /api/admin/ai-setting：查看当前配置。
     * 返回 AiSettingVO，含脱敏后的 apiKeyMasked 与各提供方预置参数，
     * 绝不包含明文 Key；仅 ADMIN 可调用。
     */
    @GetMapping
    public ApiResponse<AiSettingVO> view() {
        return ApiResponse.ok(aiConfigService.view());
    }

    /**
     * PUT /api/admin/ai-setting：更新运行时配置。
     * 入参 @Valid UpdateAiSettingRequest，返回脱敏后的 AiSettingVO；
     * 成功后清网关缓存，切换 Embedding 模型或维度后需另行重建索引；仅 ADMIN 可调用。
     */
    @PutMapping
    public ApiResponse<AiSettingVO> update(@Valid @RequestBody UpdateAiSettingRequest request) {
        return ApiResponse.ok(aiConfigService.update(request));
    }

    /**
     * POST /api/admin/ai-setting/test：连接测试。
     *
     * <p>入参 @RequestBody UpdateAiSettingRequest，刻意不加 @Valid：
     * 允许只填待验证字段，缺失项由 Service 回退当前配置，便于保存前局部探测。
     * 返回 chat / embedding 两路探测结果，不落库、不清缓存、不改动配置；仅 ADMIN 可调用。
     */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(@RequestBody UpdateAiSettingRequest request) {
        return ApiResponse.ok(aiConfigService.test(request));
    }
}
