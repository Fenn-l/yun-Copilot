# 云枢 Copilot 快速启动

## 首次启动

1. 安装 JDK 17、MySQL 8、Node.js 20+。
2. 确认 MySQL 服务运行且 root 密码为 `zxcv1234`（或设置 `MYSQL_PASSWORD`）。
3. 如使用百炼，在 PowerShell 设置 `DASHSCOPE_API_KEY`。当前开发配置内含本机演示 Key，不建议生产使用。
4. 在项目根目录执行：

```powershell
.\scripts\dev.ps1
```

脚本自动安装前端依赖、构建 Vue 静态页面、运行 Maven 单元测试并启动后端。

打开：`http://localhost:8080`

## 演示账号

- 管理员：`admin / admin123`
- 客服：`agent / agent123`
- 员工：`user / user123`

## 日常启动

前端已构建后可运行 `scripts/start.bat`（或 `mvn spring-boot:run`）。

## 本地 Ollama

```powershell
ollama pull deepseek-r1:8b
ollama pull nomic-embed-text
ollama serve
```

以管理员身份进入「系统设置 → AI 模型配置」，选择 Ollama 并保存。切换 Embedding 后点击「重建全部向量索引」。

## 验证

- `mvn test`
- `node scripts/smoke-test.mjs`
- `node frontend/scripts/ui-check.mjs`（需安装 Chrome）
- `node scripts/ollama-check.mjs`（可选；切换并重建两次向量索引，耗时较长）
