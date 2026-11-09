# API 接口参考

Base URL：`http://localhost:8080`。所有响应均为 `ApiResponse`：`{ "code": 0, "message": "ok", "data": ... }`。除登录、注册和静态文件外，接口需要 `Authorization: Bearer <JWT>`。

## 认证与用户

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录，返回 JWT 与用户 |
| POST | `/api/auth/register` | 公开 | 注册，固定赋予 USER 角色 |
| GET | `/api/auth/me` | 已登录 | 当前用户 |
| GET | `/api/admin/users` | ADMIN | 用户分页，可按角色、关键词筛选 |
| GET | `/api/users/agents` | AGENT/ADMIN | 客服与管理员列表 |
| PUT | `/api/admin/users/{id}/role` | ADMIN | 修改角色 |
| PUT | `/api/admin/users/{id}/status` | ADMIN | 启用/禁用用户 |

登录请求：`{ "username": "user", "password": "user123" }`。

## 会话与 AI

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/conversations` | 已登录 | 创建会话 |
| GET | `/api/conversations/page` | 已登录 | 本人会话分页；客服可查看工作区会话 |
| GET | `/api/conversations/{id}` | 已登录 | 会话详情 |
| GET | `/api/conversations/{id}/messages` | 已登录 | 消息历史 |
| POST | `/api/conversations/{id}/close` | 已登录 | 结束会话 |
| POST | `/api/ai/chat` | 已登录 | RAG 问答 |
| POST | `/api/ai/handoff` | 已登录 | 将会话转为工单 |
| POST | `/api/ai/feedback` | 已登录 | AI 答案点赞/点踩 |
| GET | `/api/ai/runtime` | 已登录 | 当前提供方与模型名（不含 Key） |
| GET | `/api/ai/runs/{id}` | 已登录 | 查询 AI 调用记录 |

问答请求：`{ "conversationId": 1, "message": "公司邮箱登录失败怎么办？", "knowledgeBaseId": 2, "topK": 5 }`。

正常响应中的 `data`：

```json
{
  "conversationId": 1,
  "messageId": 12,
  "answer": "请先确认账号格式... [1]",
  "citations": [{
    "chunkId": 32,
    "documentId": 4,
    "documentName": "企业邮箱手册.pdf",
    "pageNumber": 3,
    "chunkIndex": 2,
    "score": 0.82,
    "snippet": "邮箱登录失败时..."
  }],
  "handoffRequired": false,
  "reason": null,
  "latencyMs": 1180,
  "provider": "BAILIAN",
  "model": "qwen-plus"
}
```

转人工条件：知识不足（`INSUFFICIENT_KNOWLEDGE`）、AI 服务不可用（`AI_UNAVAILABLE`）或用户明确要求（`USER_REQUEST`）。模型不会直接写入工单。

## 知识库与文档

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/knowledge-bases` | 已登录 | 返回当前用户可见知识库 |
| POST | `/api/knowledge-bases` | 已登录 | 创建知识库；非客服只能创建私有库 |
| GET | `/api/knowledge-bases/{id}` | 已登录 | 知识库详情 |
| PUT | `/api/knowledge-bases/{id}` | 创建人/ADMIN | 修改知识库信息 |
| DELETE | `/api/knowledge-bases/{id}` | 创建人/ADMIN | 删除知识库及文档/切片 |
| POST | `/api/documents/upload` | 可写知识库 | multipart：`knowledgeBaseId`、`file` |
| GET | `/api/documents/page` | 已登录 | 文档分页，可按知识库、状态、名称过滤 |
| GET | `/api/documents/{id}` | 已登录 | 文档状态与错误信息 |
| GET | `/api/documents/{id}/chunks` | 已登录 | 切片预览 |
| POST | `/api/documents/{id}/reprocess` | 可写知识库 | 重新解析与向量化 |
| POST | `/api/documents/reprocess-all` | ADMIN | 重建全部向量索引 |
| DELETE | `/api/documents/{id}` | 可写知识库 | 删除文件、切片与向量 |

允许文件：PDF、DOC、DOCX、TXT、MD；最大 50MB。文档处理状态：`UPLOADED`、`PROCESSING`、`COMPLETED`、`FAILED`。

## 工单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/tickets` | 已登录 | 创建工单 |
| GET | `/api/tickets/page` | 已登录 | 本人工单；客服可筛选全部/我的工单 |
| GET | `/api/tickets/{id}` | 所有者/客服 | 工单详情与事件时间线 |
| POST | `/api/tickets/{id}/assign` | AGENT/ADMIN | 分配处理人，NEW/ASSIGNED → ASSIGNED |
| POST | `/api/tickets/{id}/start` | AGENT/ADMIN | 接单并开始处理 |
| POST | `/api/tickets/{id}/reply` | 所有者/处理客服 | 添加回复事件；等待用户时客服回复会恢复 IN_PROGRESS |
| POST | `/api/tickets/{id}/wait-user` | AGENT/ADMIN | IN_PROGRESS → WAITING_USER |
| POST | `/api/tickets/{id}/resolve` | AGENT/ADMIN | IN_PROGRESS/WAITING_USER → RESOLVED |
| POST | `/api/tickets/{id}/close` | 所有者/客服 | RESOLVED → CLOSED |
| POST | `/api/tickets/{id}/reopen` | 所有者/客服 | RESOLVED/CLOSED → IN_PROGRESS |
| POST | `/api/tickets/{id}/attributes` | AGENT/ADMIN | 更新合法分类与优先级 |

状态变更请求支持 `{ "content": "处理说明" }`；分配请求 `{ "assigneeId": 2 }`。写入失败、状态冲突返回 HTTP 409。

## Copilot、看板与模型配置

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/copilot/tickets/{id}/summary` | AGENT/ADMIN | 工单摘要 |
| POST | `/api/copilot/tickets/{id}/classify` | AGENT/ADMIN | 分类/优先级建议 JSON，后端校验枚举 |
| POST | `/api/copilot/tickets/{id}/reply-suggestion` | AGENT/ADMIN | 基于工单、历史与知识库生成回复草稿 |
| GET | `/api/admin/stats/overview` | AGENT/ADMIN | 工单、AI、知识库统计和趋势 |
| GET | `/api/admin/ai-setting` | ADMIN | 查看 AI 配置；API Key 始终脱敏 |
| PUT | `/api/admin/ai-setting` | ADMIN | 更新/切换 AI 配置 |
| POST | `/api/admin/ai-setting/test` | ADMIN | 测试对话与 Embedding API |

更新配置示例：

```json
{
  "provider": "OLLAMA",
  "baseUrl": "http://localhost:11434/v1",
  "apiKey": "ollama",
  "chatModel": "deepseek-r1:8b",
  "embeddingModel": "nomic-embed-text",
  "embeddingDim": 768,
  "temperature": 0.3,
  "maxTokens": 512
}
```

百炼示例：`provider=BAILIAN`、`baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1`、模型 `qwen-plus` / `text-embedding-v4`、维度 1024。系统按提供方独立保存 API Key，切换提供方时 `apiKey` 留空即可沿用该提供方已保存的 Key。切换 Embedding 模型后必须调用「重建全部向量索引」。
