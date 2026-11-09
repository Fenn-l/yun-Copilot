# 架构说明

## 1. 总体结构

```
Vue 3 SPA (Element Plus + ECharts)
        │  /api/**  (JWT Bearer)
        ▼
Spring Boot 3 ── Spring Security / JWT ── 角色与数据权限
   │
   ├── 会话中心 ConversationService
   ├── 知识库 KnowledgeBase / DocumentService / DocumentProcessService
   ├── RAG VectorRetriever + AiChatService
   ├── 工单 TicketService + TicketStateMachine
   ├── Copilot CopilotService
   └── AI 网关 SpringAiLlmGateway ── Dashboard StatsService
        │
        ▼
MySQL 8（业务元数据 + kb_chunk 向量 BLOB）
文档文件：data/uploads（可替换为对象存储）
```

后端按业务域分包：`common`、`system`、`conversation`、`knowledge`、`ai`、`ticket`、`copilot`、`dashboard`、`seed`。AI 调用统一经过 `LlmGateway`，便于替换模型提供方。

## 2. 数据模型

| 表 | 说明 |
|---|---|
| `cs_user` | 用户、BCrypt 密码、角色与状态 |
| `cs_conversation` | 会话主表，状态 ACTIVE / CLOSED |
| `cs_message` | 消息，角色 USER / ASSISTANT，`citations` 存引用 JSON，`handoff_required` 标记转人工 |
| `kb_base` | 知识库，`visibility` PUBLIC / INTERNAL / PRIVATE |
| `kb_document` | 文档，`status` UPLOADED / PROCESSING / COMPLETED / FAILED，`process_version` 控制重处理 |
| `kb_chunk` | 切片、页码、字符数、`embedding`（float32 序列化 BLOB）、`embedding_model`、`embedding_dim` |
| `cs_ticket` | 工单，`status` 状态机字段，`version` 乐观锁 |
| `cs_ticket_event` | 工单事件流水（创建、分配、接单、回复、解决、关闭、重开、属性调整） |
| `cs_ai_run` | AI 调用日志：场景、提供方、模型、Token、耗时、状态、错误 |
| `cs_feedback` | AI 回答评价（点赞/点踩 + 评论） |
| `ai_setting` | 单行运行时 AI 配置（提供方、Base URL、Key、模型、维度、温度、最大 Token） |
| `ai_provider_key` | 按提供方独立保存的 API Key，模型提供方切换后无需重复输入 |

约束与索引：用户名、工单号、会话号唯一；会话按 `(user_id, status, update_time)`，消息按 `(conversation_id, id)`，工单按 `(status, assignee_id, create_time)` 建索引。

## 3. RAG 链路

1. 前端 `POST /api/ai/chat`。
2. 后端保存 USER 消息，读取最近 8 条历史消息组成的上下文窗口。
3. `VectorRetriever`：
   - 依据当前用户角色与知识库可见范围计算 `visibleIds`；
   - 只取 `embedding_model = 当前 Embedding 模型` 且文档 `COMPLETED` 的切片；
   - 对问题调用 Embedding，按余弦相似度排序，按阈值与 Top-K 截断。
4. 组装 Prompt：System 规则 + 历史对话 + `【知识库资料】[1] 来源...` + 用户问题。
5. 调用 LLM：
   - 知识不足或敏感操作：模型只输出 `[[INSUFFICIENT]]`；
   - 正常回答：模型用 `[1][2]` 引用资料编号。
6. 后端校验引用：
   - 只保留编号在检索结果范围内的引用；
   - 清理越界编号，防止模型编造来源；
   - 若模型未给有效编号，返回检索到的前 3 条真实来源。
7. 保存 ASSISTANT 消息与引用 JSON，记录 `cs_ai_run`（Token、耗时、模型），返回 `handoffRequired`。
8. 检索失败或模型调用失败时：返回兜底话术并标记 `AI_UNAVAILABLE`，用户可直接转人工创建工单，保证业务流程不被 AI 故障阻塞。

## 4. 文档处理

上传接口只完成文件落盘与 `kb_document` 插入，然后提交异步任务：

```
UPLOADED ──► PROCESSING ──► COMPLETED
                     └────► FAILED（记录 error_message，可重处理）
```

- 解析：PDFBox 保留页码；POI 解析 docx/doc，表格文本一并抽取；TXT/MD 自动识别 UTF-8 / GB18030。
- 切片：按段落聚合到约 420 字符，长段落按句切分，切片间保留 60 字符重叠，保留页码。
- 向量化：分批调用 Embedding，向量序列化为 float32 BLOB 写入 `kb_chunk`。
- 事务边界：解析与外部模型调用在事务外；写入阶段由 `DocumentIndexWriter` 在事务内执行“校验版本 → 删除旧切片 → 批量写入 → 更新文档状态”。
- 幂等与并发：重处理递增 `process_version`；写入前校验版本和状态，旧任务无法覆盖新结果。

## 5. 工单状态机

```
NEW ──► ASSIGNED ──► IN_PROGRESS ──► RESOLVED ──► CLOSED
          │              ▲  │             │          │
          └──────────────┘  ▼             └──────────┘
                     WAITING_USER          重开回到 IN_PROGRESS
```

- 所有变更通过 `TicketStateMachine.assertTransition` 校验；
- 更新 SQL 条件包含 `id + version + 原状态`，更新成功时 `version + 1`；失败返回 409；
- 客服只能处理分配给自己的工单（管理员例外）；普通用户只能查看/回复自己的工单；用户可在已解决工单上确认关闭或重开。

## 6. 权限模型

- 路由级：Spring Security 对所有 `/api/**` 做 JWT 认证；管理接口使用 `@PreAuthorize`。
- 数据级：会话、消息、工单、文档均在 Service 层校验归属或角色。
- 知识库：`PUBLIC` 全员、`INTERNAL` 客服/管理员、`PRIVATE` 创建人/管理员；权限条件先于向量检索执行，而非依赖 Prompt。
- 前端菜单与按钮仅做体验优化，不作为安全边界。

## 7. AI 模型切换

`ai_setting` 保存运行时配置，`SpringAiLlmGateway` 使用 Spring AI 的 `OpenAiApi` / `OpenAiChatModel` / `OpenAiEmbeddingModel` 构建客户端，并按配置哈希缓存。

- 百炼：`https://dashscope.aliyuncs.com/compatible-mode/v1`，路径 `/chat/completions`、`/embeddings`。
- Ollama：`http://localhost:11434/v1`。
- 切换后清空模型缓存；Embedding 模型或维度变化时，需要「重建全部向量索引」，否则旧切片因模型/维度不匹配不参与检索。

## 8. 主要非功能设计

- 统一返回结构、业务异常与全局异常处理。
- JWT 无状态会话，接口无状态可水平扩展。
- 外部 AI 调用设置连接/读取超时，失败降级为转人工。
- AI 调用可观测：`cs_ai_run` 记录所有场景耗时和状态，看板聚合展示。
- 乐观锁 + 状态机保证工单并发安全；异步处理保证上传接口快速返回。
