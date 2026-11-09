// 验证「本地 Ollama / 阿里云百炼」运行时切换：
// 1. 切换至 Ollama → 重建全部向量索引 → 用 Ollama 完成一次 RAG 问答
// 2. 切回百炼 → 重建索引 → 再次验证 RAG 可用
// 运行：node scripts/ollama-check.mjs

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const BAILIAN_KEY = process.env.DASHSCOPE_API_KEY || 'sk-ws-H.YRLLPYI.HmnD.MEYCIQCyqK-_8oByDA9OlMmMhe_5_X4aGA2ES66WDk5BZn3CFAIhAPGEivBJaNQlXShToaxwdymilEPpk7dJJWqf-4qrY_yj'

const OLLAMA = {
  provider: 'OLLAMA',
  baseUrl: 'http://localhost:11434/v1',
  apiKey: 'ollama',
  chatModel: 'deepseek-r1:8b',
  embeddingModel: 'nomic-embed-text',
  embeddingDim: 768,
  temperature: 0.3,
  maxTokens: 900,
}

const BAILIAN = {
  provider: 'BAILIAN',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  apiKey: BAILIAN_KEY,
  chatModel: 'qwen-plus',
  embeddingModel: 'text-embedding-v4',
  embeddingDim: 1024,
  temperature: 0.3,
  maxTokens: 1500,
}

async function api(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  return { status: res.status, json: text ? JSON.parse(text) : null }
}

async function login(username, password) {
  const res = await api('POST', '/api/auth/login', null, { username, password })
  if (res.status !== 200) throw new Error('登录失败 ' + username)
  return res.json.data
}

async function waitDocsCompleted(admin, timeoutMs = 300000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const res = await api('GET', '/api/documents/page?size=50', admin.token)
    const list = res.json.data.list
    const pending = list.filter((doc) => ['UPLOADED', 'PROCESSING'].includes(doc.status))
    const failed = list.filter((doc) => doc.status === 'FAILED')
    if (!pending.length) {
      return { failed, docs: list }
    }
    await new Promise((r) => setTimeout(r, 3000))
  }
  throw new Error('等待文档处理超时')
}

async function ragChat(user, question) {
  const conv = await api('POST', '/api/conversations', user.token, { title: 'Ollama 切换验证' })
  const conversationId = conv.json.data.id
  const started = Date.now()
  const res = await api('POST', '/api/ai/chat', user.token, { conversationId, message: question })
  const elapsed = Date.now() - started
  if (res.status !== 200) throw new Error('问答失败 ' + JSON.stringify(res.json))
  return { data: res.json.data, elapsed }
}

async function main() {
  const admin = await login('admin', 'admin123')
  const user = await login('user', 'user123')
  const question = '公司邮箱登录失败，提示账号认证异常，应该怎么处理？'

  console.log('\n[1] 切换模型提供方为本地 Ollama')
  let res = await api('PUT', '/api/admin/ai-setting', admin.token, OLLAMA)
  if (res.status !== 200) throw new Error('切换失败 ' + JSON.stringify(res.json))
  console.log('  当前提供方:', res.json.data.provider, res.json.data.chatModel)

  console.log('\n[2] 重建全部向量索引（nomic-embed-text / 768 维）')
  const rebuild = await api('POST', '/api/documents/reprocess-all', admin.token)
  console.log('  提交文档数:', rebuild.json.data)
  const done1 = await waitDocsCompleted(admin)
  console.log('  处理完成，失败文档数:', done1.failed.length)
  const doc1 = done1.docs[0]
  console.log('  示例文档:', doc1.documentName, doc1.status, doc1.chunkCount, '切片', doc1.embeddingModel, doc1.embeddingDim, '维')

  console.log('\n[3] 使用 Ollama 进行 RAG 问答')
  const chat1 = await ragChat(user, question)
  console.log('  耗时:', chat1.elapsed, 'ms · 模型:', chat1.data.model, '· 引用:', chat1.data.citations.length)
  console.log('  回答:', (chat1.data.answer || '').replace(/\n/g, ' ').slice(0, 220))
  const ollamaOk = chat1.data.answer && chat1.data.answer.length > 20 && !chat1.data.handoffRequired
  console.log('  Ollama RAG 问答成功:', ollamaOk)

  console.log('\n[4] 切回阿里云百炼并重建索引（API Key 留空，验证按提供方保存的 Key 自动恢复）')
  res = await api('PUT', '/api/admin/ai-setting', admin.token, { ...BAILIAN, apiKey: '' })
  if (res.status !== 200) throw new Error('切回失败 ' + JSON.stringify(res.json))
  console.log('  当前提供方:', res.json.data.provider, res.json.data.chatModel)
  await api('POST', '/api/documents/reprocess-all', admin.token)
  const done2 = await waitDocsCompleted(admin)
  console.log('  重建完成，失败文档数:', done2.failed.length)

  console.log('\n[5] 使用百炼再次进行 RAG 问答')
  const chat2 = await ragChat(user, question)
  console.log('  耗时:', chat2.elapsed, 'ms · 模型:', chat2.data.model, '· 引用:', chat2.data.citations.length)
  console.log('  回答:', (chat2.data.answer || '').replace(/\n/g, ' ').slice(0, 120), '...')
  const bailianOk = chat2.data.answer && chat2.data.citations.length > 0
  console.log('  百炼 RAG 问答成功:', bailianOk)

  if (!ollamaOk || !bailianOk) {
    console.error('\n模型切换验证未完全通过')
    process.exit(1)
  }
  console.log('\n模型提供方切换验证通过：Ollama / 百炼均可完成 RAG 问答\n')
}

main().catch((error) => {
  console.error('验证异常:', error.message)
  process.exit(1)
})
