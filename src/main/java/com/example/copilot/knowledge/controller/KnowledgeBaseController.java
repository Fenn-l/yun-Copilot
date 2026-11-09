package com.example.copilot.knowledge.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.copilot.knowledge.dto.KnowledgeBaseVO;
import com.example.copilot.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库 REST 控制器，挂载在 {@code /api/knowledge-bases}，统一用 {@link ApiResponse} 包装响应体。
 *
 * <p>在 RAG 链路中的位置：管理知识库这一「检索权限边界」的 CRUD 入口。知识库的可见范围
 * （PUBLIC / INTERNAL / PRIVATE）直接决定 {@link com.example.copilot.knowledge.retrieval.VectorRetriever}
 * 能检索到哪些切片，因此这里的创建/更新/删除都会影响后续检索结果集。
 * 请求体用 {@code @Valid} 做基础校验，权限与可见范围约束由 {@link KnowledgeBaseService} 落实。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    /** 知识库业务服务，由构造器注入。 */
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 列出当前登录用户可见的知识库。
     *
     * <p>权限：Service 按角色与可见范围过滤（PUBLIC 全员 / INTERNAL 客服+管理员 / PRIVATE 创建人+管理员）。
     * 每个 VO 附带文档数、切片数、拥有者昵称等聚合信息。
     *
     * @return 可见知识库 VO 列表
     */
    @GetMapping
    public ApiResponse<List<KnowledgeBaseVO>> list() {
        return ApiResponse.ok(knowledgeBaseService.listVisible());
    }

    /**
     * 创建知识库。
     *
     * <p>参数：{@code @Valid} 校验请求体（name 等）；visibility 缺省 PRIVATE。
     * <p>权限规则：普通用户（非客服）只能创建 PRIVATE，试图建 PUBLIC / INTERNAL 会被 Service 拒绝（403）；
     * 拥有者自动绑定当前用户。
     *
     * @param request 创建请求（name / description / visibility）
     * @return 新建知识库 VO
     */
    @PostMapping
    public ApiResponse<KnowledgeBaseVO> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(request));
    }

    /**
     * 查询单个知识库详情。
     *
     * <p>权限：知识库需存在、ACTIVE 且对当前用户可读，否则 404 / 403。
     *
     * @param id 知识库主键（路径变量）
     * @return 知识库 VO
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseService.detail(id));
    }

    /**
     * 更新知识库名称、描述与可见范围。
     *
     * <p>参数：{@code @Valid} 校验请求体；visibility 缺省沿用原值。
     * <p>权限规则：调用方需可写；普通用户必须保持 PRIVATE，不能把库提权为 PUBLIC / INTERNAL（403）。
     *
     * @param id      知识库主键（路径变量）
     * @param request 更新请求
     * @return 更新后的知识库 VO
     */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseVO> update(@PathVariable Long id,
                                               @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.update(id, request));
    }

    /**
     * 删除知识库，并级联清理其文档、切片与落盘文件。
     *
     * <p>权限：仅知识库创建人或管理员可删（比可写权限更严，客服不能删他人创建的共享库）。
     * 删除后该库下所有向量不再可被检索。成功返回无 data 的 {@link ApiResponse}。
     *
     * @param id 知识库主键（路径变量）
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return ApiResponse.ok();
    }
}
