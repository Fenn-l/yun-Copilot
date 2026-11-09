package com.example.copilot.knowledge.controller;

import com.example.copilot.common.result.ApiResponse;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.knowledge.dto.ChunkVO;
import com.example.copilot.knowledge.dto.DocumentVO;
import com.example.copilot.knowledge.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档 REST 控制器，挂载在 {@code /api/documents}，统一用 {@link ApiResponse} 包装响应体。
 *
 * <p>在 RAG 链路中的位置：它是文档写链路（上传/重处理/删除）与读链路（列表/详情/切片）的 HTTP 门面，
 * 本身不含业务逻辑，全部委托 {@link DocumentService}；具体的权限判定、状态机、版本号抢占都在 Service 层。
 * 类上的 {@code @RestController} + {@code @RequestMapping} 定义基础路径，登录主体由安全过滤器写入
 * 线程上下文，Service 通过 {@code CurrentUser.get()} 取得。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    /** 文档业务服务，由构造器注入。 */
    private final DocumentService documentService;

    /**
     * 上传文档到指定知识库。
     *
     * <p>权限：Service 内 {@code requireWritable} 校验当前用户可写目标知识库；类型/大小白名单同在 Service。
     * <p>参数：{@code knowledgeBaseId}（必填）、{@code file}（multipart 文件，必填）。
     * <p>状态语义：接口只做落盘 + 建记录 + 提交异步任务便快速返回，返回时文档多为 UPLOADED，
     * 前端需轮询 page/detail 等待 COMPLETED 或 FAILED。
     *
     * @param knowledgeBaseId 目标知识库 ID
     * @param file            上传文件
     * @return 新建文档的 VO
     */
    @PostMapping("/upload")
    public ApiResponse<DocumentVO> upload(@RequestParam Long knowledgeBaseId,
                                          @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(knowledgeBaseId, file));
    }

    /**
     * 分页查询文档列表。
     *
     * <p>权限：只返回当前用户可见知识库下的文档（Service 基于 visible 过滤）。
     * <p>参数：page（默认 1）、size（默认 10）、knowledgeBaseId / status / keyword 均可选；
     * status 非法值会被忽略，指定的知识库不可见时抛 403。
     *
     * @param page            页码
     * @param size            每页条数
     * @param knowledgeBaseId 可选，按知识库过滤
     * @param status          可选，按状态过滤
     * @param keyword         可选，按文档名模糊匹配
     * @return 文档 VO 分页结果
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<DocumentVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) Long knowledgeBaseId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(documentService.page(page, size, knowledgeBaseId, status, keyword));
    }

    /**
     * 查询单个文档详情。
     *
     * <p>权限：文档及其所属知识库均需可读。返回含处理状态、失败原因、切片数、Embedding 模型与维度等信息，
     * 用于前端展示异步处理进度。
     *
     * @param id 文档主键（路径变量）
     * @return 文档 VO
     */
    @GetMapping("/{id}")
    public ApiResponse<DocumentVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(documentService.detail(id));
    }

    /**
     * 查看某文档的切片列表（调试/审计）。
     *
     * <p>权限：文档需可读。参数 limit 可选，缺省 100、上限 500；返回切片正文、页码、序号，
     * 不含向量 BLOB。
     *
     * @param id    文档主键（路径变量）
     * @param limit 可选，返回条数上限
     * @return 切片 VO 列表
     */
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<ChunkVO>> chunks(@PathVariable Long id,
                                             @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(documentService.chunks(id, limit));
    }

    /**
     * 重处理单个文档（重新解析 + 向量化）。
     *
     * <p>权限：文档可读且知识库可写。Service 会递增 process_version、重置为 UPLOADED 并提交异步任务；
     * 若文档正处于 PROCESSING，返回 409 冲突。
     *
     * @param id 文档主键（路径变量）
     * @return 重置后的文档 VO（版本已递增）
     */
    @PostMapping("/{id}/reprocess")
    public ApiResponse<DocumentVO> reprocess(@PathVariable Long id) {
        return ApiResponse.ok(documentService.reprocess(id));
    }

    /**
     * 重建全部文档的向量索引，仅管理员可用。
     *
     * <p>使用场景：运行时切换 Embedding 模型（百炼 / Ollama）后必须执行，否则旧模型向量会被检索过滤掉。
     * 逐篇递增版本号并提交异步任务，跳过正在处理的文档；返回成功触发的任务数。
     *
     * @return 实际触发重建的文档数量
     */
    @PostMapping("/reprocess-all")
    public ApiResponse<Integer> reprocessAll() {
        return ApiResponse.ok(documentService.reprocessAll());
    }

    /**
     * 删除文档及其切片、落盘文件。
     *
     * <p>权限：文档可读且知识库可写。删除后正在跑的异步任务会因版本/记录不存在而被安全丢弃。
     * 成功返回无 data 的 {@link ApiResponse}。
     *
     * @param id 文档主键（路径变量）
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ApiResponse.ok();
    }
}
