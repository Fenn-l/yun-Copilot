<template>
  <div class="chat-page">
    <aside class="panel conv-panel">
      <div class="conv-head">
        <div>
          <div class="conv-title">会话</div>
          <div class="conv-sub">最近 {{ conversations.length }} 个对话</div>
        </div>
        <el-button circle type="primary" :icon="Plus" size="small" @click="newConversation" />
      </div>
      <div class="conv-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === activeId }"
          @click="selectConversation(conv.id)"
        >
          <div class="conv-item-top">
            <span class="conv-item-title">{{ conv.title }}</span>
            <span class="conv-status" :class="conv.status">{{ conv.status === 'ACTIVE' ? '进行中' : '已结束' }}</span>
          </div>
          <div class="conv-item-preview">{{ conv.lastMessage || '暂无消息，点击开始提问' }}</div>
          <div class="conv-item-meta">
            <span v-if="auth.isAgent && conv.userName">{{ conv.userName }}</span>
            <span>{{ fromNow(conv.updateTime) }}</span>
          </div>
        </div>
        <div v-if="!conversations.length && !loadingList" class="empty-hint">
          还没有会话<br />点击右上角「+」开始咨询
        </div>
      </div>
    </aside>

    <section class="panel chat-panel">
      <header class="chat-head">
        <div class="chat-head-left">
          <div class="chat-title">
            {{ activeConversation?.title || '新的对话' }}
            <el-tag v-if="activeConversation" size="small" :type="activeConversation.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
              {{ activeConversation.status === 'ACTIVE' ? '进行中' : '已结束' }}
            </el-tag>
          </div>
          <div class="chat-meta">
            <span v-if="activeConversation" class="mono">{{ activeConversation.conversationNo }}</span>
            <span v-if="auth.isAgent && activeConversation?.userName">提交人：{{ activeConversation.userName }}</span>
            <span>上下文保留最近 8 条消息</span>
          </div>
        </div>
        <div class="flex gap-8">
          <el-button v-if="activeConversation && activeConversation.status === 'ACTIVE'" text :icon="Service" @click="openHandoff(null)">
            转人工
          </el-button>
          <el-button v-if="activeConversation && activeConversation.status === 'ACTIVE'" text :icon="CircleClose" @click="closeActive">
            结束会话
          </el-button>
        </div>
      </header>

      <div ref="scrollRef" class="chat-body">
        <div v-if="!messages.length" class="welcome">
          <div class="welcome-logo">
            <svg width="34" height="34" viewBox="0 0 64 64" fill="none">
              <path d="M16 22c0-3.3 2.7-6 6-6h20c3.3 0 6 2.7 6 6v13c0 3.3-2.7 6-6 6H31l-9 7v-7h-0c-3.3 0-6-2.7-6-6V22z" fill="#fff"/>
              <circle cx="25" cy="28" r="2.4" fill="#0d9488"/>
              <circle cx="32" cy="28" r="2.4" fill="#0d9488"/>
              <circle cx="39" cy="28" r="2.4" fill="#0d9488"/>
            </svg>
          </div>
          <h2>你好，我是云枢 AI 客服</h2>
          <p>我会先在企业知识库中检索资料，再基于资料回答，并给出可核验的来源引用。知识不足时会引导你转人工。</p>
          <div class="suggestions">
            <div v-for="item in suggestions" :key="item" class="suggestion" @click="askSuggestion(item)">
              <el-icon><ChatLineRound /></el-icon>
              <span>{{ item }}</span>
            </div>
          </div>
        </div>

        <div v-for="(msg, index) in messages" :key="msg.id || index" class="msg" :class="msg.role.toLowerCase()">
          <div v-if="msg.role === 'ASSISTANT'" class="msg-avatar assistant-avatar">
            <svg width="17" height="17" viewBox="0 0 64 64" fill="none">
              <path d="M16 22c0-3.3 2.7-6 6-6h20c3.3 0 6 2.7 6 6v13c0 3.3-2.7 6-6 6H31l-9 7v-7h-0c-3.3 0-6-2.7-6-6V22z" fill="#fff"/>
              <circle cx="25" cy="28" r="2.6" fill="#0d9488"/>
              <circle cx="32" cy="28" r="2.6" fill="#0d9488"/>
              <circle cx="39" cy="28" r="2.6" fill="#0d9488"/>
            </svg>
          </div>

          <div class="msg-main">
            <div v-if="msg.loading" class="msg-bubble assistant-bubble loading-bubble">
              <span class="dot-pulse"></span>
              正在检索知识库并生成回答...
            </div>

            <div v-else-if="msg.role === 'USER'" class="msg-bubble user-bubble">
              {{ msg.content }}
            </div>

            <div v-else class="msg-bubble assistant-bubble">
              <MarkdownView :content="msg.content" />

              <div v-if="msg.citations && msg.citations.length" class="citations">
                <div class="citations-head">
                  <el-icon><Link /></el-icon>
                  知识来源 · {{ msg.citations.length }} 条（后端已校验）
                </div>
                <div class="citation-list">
                  <div
                    v-for="(cite, citeIndex) in msg.citations"
                    :key="cite.chunkId"
                    class="citation"
                    @click="openCitation(cite, citeIndex)"
                  >
                    <span class="cite-index">{{ citeIndex + 1 }}</span>
                    <span class="cite-name">{{ cite.documentName }}</span>
                    <span v-if="cite.pageNumber" class="cite-page">第 {{ cite.pageNumber }} 页</span>
                    <span class="cite-score">{{ Math.round((cite.score || 0) * 100) }}%</span>
                  </div>
                </div>
              </div>

              <div v-if="msg.handoffRequired" class="handoff-bar">
                <div class="handoff-text">
                  <el-icon><WarningFilled /></el-icon>
                  <span>{{ handoffReasonText(msg.reason) }}</span>
                </div>
                <el-button
                  v-if="!msg.handoffDone"
                  type="primary"
                  size="small"
                  @click="openHandoff(msg)"
                >
                  创建工单 / 转人工
                </el-button>
                <el-tag v-else type="success" effect="plain" size="small">工单已创建</el-tag>
              </div>

              <div class="msg-foot">
                <span>{{ fromNow(msg.createTime) }}</span>
                <span v-if="msg.latencyMs" class="muted">{{ msg.latencyMs }}ms</span>
                <div class="feedback" v-if="msg.id && !msg.loading">
                  <el-icon
                    class="feedback-icon"
                    :class="{ active: feedbackMap[msg.id] === 1 }"
                    @click="sendFeedback(msg, 1)"
                  ><CaretTop /></el-icon>
                  <el-icon
                    class="feedback-icon"
                    :class="{ active: feedbackMap[msg.id] === 0 }"
                    @click="sendFeedback(msg, 0)"
                  ><CaretBottom /></el-icon>
                </div>
              </div>
            </div>

            <div v-if="msg.role === 'USER'" class="msg-time">{{ fromNow(msg.createTime) }}</div>
          </div>

          <div v-if="msg.role === 'USER'" class="msg-avatar user-avatar">{{ userInitial }}</div>
        </div>
      </div>

      <footer class="composer">
        <div v-if="activeConversation?.status === 'CLOSED'" class="composer-closed">
          该会话已结束，新建会话后可继续咨询。
          <el-button size="small" type="primary" plain @click="newConversation">新建会话</el-button>
        </div>
        <template v-else>
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            resize="none"
            placeholder="描述你的问题，例如：公司邮箱登录失败提示账号认证异常怎么办？"
            @keydown="onKeydown"
          />
          <div class="composer-bar">
            <div class="flex gap-8">
              <el-select v-model="kbId" size="small" placeholder="全部知识库" clearable style="width: 170px">
                <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
              <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
            </div>
            <el-button type="primary" :icon="Promotion" :loading="sending" @click="send">
              发送
            </el-button>
          </div>
        </template>
      </footer>
    </section>

    <el-dialog v-model="citationVisible" title="知识来源" width="620px">
      <div v-if="currentCitation" class="citation-detail">
        <div class="citation-detail-head">
          <el-icon size="18"><Document /></el-icon>
          <div>
            <div class="citation-doc">{{ currentCitation.documentName }}</div>
            <div class="muted" style="font-size: 12px">
              {{ currentCitation.knowledgeBaseName }}
              <span v-if="currentCitation.pageNumber"> · 第 {{ currentCitation.pageNumber }} 页</span>
              <span> · 切片 #{{ currentCitation.chunkIndex }}</span>
              <span> · 相似度 {{ Math.round((currentCitation.score || 0) * 100) }}%</span>
            </div>
          </div>
        </div>
        <div class="citation-snippet">{{ currentCitation.snippet }}</div>
        <div class="muted" style="font-size: 12px; margin-top: 10px">
          提示：引用由后端根据实际检索并参与生成的切片校验，模型无法编造来源。
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="handoffVisible" title="创建工单 / 转人工" width="560px">
      <el-form :model="handoffForm" label-position="top">
        <el-form-item label="工单标题">
          <el-input v-model="handoffForm.title" maxlength="200" show-word-limit />
        </el-form-item>
        <div class="handoff-grid">
          <el-form-item label="分类">
            <el-select v-model="handoffForm.category" style="width: 100%">
              <el-option v-for="(label, key) in categoryMeta" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="handoffForm.priority" style="width: 100%">
              <el-option v-for="(item, key) in priorityMeta" :key="key" :label="item.label" :value="key" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="问题描述（已自动带入最近对话）">
          <el-input v-model="handoffForm.description" type="textarea" :rows="6" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handoffVisible = false">取消</el-button>
        <el-button type="primary" :loading="handoffLoading" @click="submitHandoff">提交工单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CaretBottom,
  CaretTop,
  ChatLineRound,
  CircleClose,
  Document,
  Link,
  Plus,
  Promotion,
  Service,
  WarningFilled,
} from '@element-plus/icons-vue'
import MarkdownView from '../components/MarkdownView.vue'
import { useAuthStore } from '../stores/auth'
import {
  chat,
  closeConversation,
  createConversation,
  feedback,
  getConversation,
  getMessages,
  handoff,
  listKnowledgeBases,
  pageConversations,
} from '../api'
import { categoryMeta, fromNow, priorityMeta } from '../utils/format'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const conversations = ref([])
const messages = ref([])
const activeId = ref(null)
const input = ref('')
const sending = ref(false)
const loadingList = ref(false)
const knowledgeBases = ref([])
const kbId = ref(null)
const feedbackMap = reactive({})
const scrollRef = ref(null)
const citationVisible = ref(false)
const currentCitation = ref(null)
const handoffVisible = ref(false)
const handoffLoading = ref(false)
const handoffTarget = ref(null)
const handoffForm = reactive({ title: '', category: 'OTHER', priority: 'MEDIUM', description: '' })

const suggestions = [
  '公司邮箱登录失败，提示账号认证异常，应该怎么处理？',
  '忘记邮箱密码怎么办，需要联系谁重置？',
  'VPN 连接频繁断开，如何排查？',
  '请假超过三天需要谁审批？',
]

const activeConversation = computed(() => conversations.value.find((item) => item.id === activeId.value) || null)
const userInitial = computed(() => (auth.displayName || 'U').slice(0, 1).toUpperCase())

function handoffReasonText(reason) {
  const map = {
    INSUFFICIENT_KNOWLEDGE: '知识库中没有足够可靠的答案，建议转人工处理。',
    AI_UNAVAILABLE: 'AI 服务暂时不可用，可以直接创建工单。',
    USER_REQUEST: '你已请求转人工，请确认工单信息后提交。',
  }
  return map[reason] || '需要人工协助处理。'
}

async function loadConversations() {
  loadingList.value = true
  try {
    const data = await pageConversations({ page: 1, size: 50 })
    conversations.value = data.list
    if (!activeId.value && conversations.value.length) {
      await selectConversation(conversations.value[0].id)
    }
  } finally {
    loadingList.value = false
  }
}

async function ensureConversation() {
  if (activeId.value) return activeId.value
  const conv = await createConversation({ title: '新的对话' })
  conversations.value.unshift(conv)
  activeId.value = conv.id
  messages.value = []
  return conv.id
}

async function selectConversation(id) {
  if (sending.value) return
  activeId.value = id
  let conv = conversations.value.find((item) => item.id === id)
  if (!conv) {
    try {
      conv = await getConversation(id)
      conversations.value.unshift(conv)
    } catch {
      return
    }
  }
  try {
    messages.value = await getMessages(id, {})
    await scrollToBottom()
  } catch {
    messages.value = []
  }
}

async function newConversation() {
  const conv = await createConversation({ title: '新的对话' })
  conversations.value.unshift(conv)
  activeId.value = conv.id
  messages.value = []
  input.value = ''
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  const convId = await ensureConversation()
  const conv = conversations.value.find((item) => item.id === convId)
  if (conv && conv.status === 'CLOSED') {
    ElMessage.warning('会话已结束，请新建会话')
    return
  }
  sending.value = true
  input.value = ''
  const now = new Date().toISOString()
  messages.value.push({ id: `local-${Date.now()}`, role: 'USER', content: text, createTime: now })
  const loadingMsg = { id: `loading-${Date.now()}`, role: 'ASSISTANT', loading: true }
  messages.value.push(loadingMsg)
  await scrollToBottom()
  try {
    const data = await chat({ conversationId: convId, message: text, knowledgeBaseId: kbId.value || null })
    const index = messages.value.findIndex((item) => item.id === loadingMsg.id)
    const reply = {
      id: data.messageId,
      role: 'ASSISTANT',
      content: data.answer,
      citations: data.citations || [],
      handoffRequired: data.handoffRequired,
      reason: data.reason,
      latencyMs: data.latencyMs,
      createTime: new Date().toISOString(),
    }
    if (index >= 0) messages.value.splice(index, 1, reply)
    const current = conversations.value.find((item) => item.id === convId)
    if (current) {
      if (current.title === '新的对话' && text.length) {
        current.title = text.length > 24 ? `${text.slice(0, 24)}...` : text
      }
      current.lastMessage = data.answer?.slice(0, 60)
      current.updateTime = new Date().toISOString()
    }
  } catch {
    messages.value = messages.value.filter((item) => item.id !== loadingMsg.id)
    input.value = text
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

function askSuggestion(text) {
  input.value = text
  send()
}

async function closeActive() {
  if (!activeId.value) return
  await ElMessageBox.confirm('结束后该会话不可继续提问，确定结束吗？', '结束会话', {
    confirmButtonText: '结束',
    cancelButtonText: '取消',
    type: 'warning',
  })
  const conv = await closeConversation(activeId.value)
  const target = conversations.value.find((item) => item.id === activeId.value)
  if (target) target.status = conv.status
  ElMessage.success('会话已结束')
}

async function sendFeedback(msg, rating) {
  try {
    await feedback({ messageId: msg.id, rating })
    feedbackMap[msg.id] = rating
    ElMessage.success('感谢你的反馈')
  } catch {
    /* 拦截器已提示 */
  }
}

function openCitation(cite) {
  currentCitation.value = cite
  citationVisible.value = true
}

function guessCategory(text) {
  if (/邮箱|账号|密码|登录|权限/.test(text)) return 'ACCOUNT'
  if (/网络|VPN|电脑|蓝屏|打印机|系统/i.test(text)) return 'TECHNICAL'
  if (/退款|售后|发票|订单/.test(text)) return 'AFTER_SALES'
  if (/产品|安装|使用|功能/.test(text)) return 'PRODUCT'
  return 'OTHER'
}

function openHandoff(msg) {
  handoffTarget.value = msg
  const title = activeConversation.value?.title && activeConversation.value.title !== '新的对话'
    ? activeConversation.value.title
    : 'AI 会话转人工'
  const lastUser = [...messages.value].reverse().find((item) => item.role === 'USER')
  const lastAi = [...messages.value].reverse().find((item) => item.role === 'ASSISTANT' && !item.loading)
  const description = [
    lastUser ? `用户问题：${lastUser.content}` : '',
    lastAi ? `AI 回复：${lastAi.content.slice(0, 600)}` : '',
  ].filter(Boolean).join('\n\n') || '用户通过 AI 会话发起转人工请求。'
  Object.assign(handoffForm, {
    title: title.length > 60 ? title.slice(0, 60) : title,
    category: guessCategory(`${title} ${lastUser?.content || ''}`),
    priority: /紧急|宕机|无法使用|无法登录/.test(`${title} ${lastUser?.content || ''}`) ? 'HIGH' : 'MEDIUM',
    description,
  })
  handoffVisible.value = true
}

async function submitHandoff() {
  if (!activeId.value) return
  if (!handoffForm.title.trim() || !handoffForm.description.trim()) {
    ElMessage.warning('请填写标题和问题描述')
    return
  }
  handoffLoading.value = true
  try {
    const ticket = await handoff({
      conversationId: activeId.value,
      title: handoffForm.title,
      category: handoffForm.category,
      priority: handoffForm.priority,
      description: handoffForm.description,
    })
    if (handoffTarget.value) handoffTarget.value.handoffDone = true
    handoffVisible.value = false
    try {
      await ElMessageBox.confirm(`工单 ${ticket.ticketNo} 已创建，是否前往工单中心查看？`, '转人工成功', {
        confirmButtonText: '查看工单',
        cancelButtonText: '留在对话',
        type: 'success',
      })
      router.push('/tickets')
    } catch {
      /* 留在当前页 */
    }
  } finally {
    handoffLoading.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}

onMounted(async () => {
  try {
    knowledgeBases.value = await listKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
  await loadConversations()
  const queryId = Number(route.query.conversationId)
  if (queryId) {
    await selectConversation(queryId)
  }
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  gap: 16px;
  padding: 18px 22px;
  box-sizing: border-box;
  overflow: hidden;
}

.conv-panel {
  width: 292px;
  flex: 0 0 292px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.conv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 12px;
  border-bottom: 1px solid var(--line);
}

.conv-title {
  font-size: 15px;
  font-weight: 700;
}

.conv-sub {
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 2px;
}

.conv-list {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.conv-item {
  padding: 11px 12px;
  border-radius: 11px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: 0.16s ease;
  margin-bottom: 4px;
}

.conv-item:hover {
  background: #f8fafc;
}

.conv-item.active {
  background: linear-gradient(180deg, #f0fdfa, #f7fffd);
  border-color: #b7ebe4;
}

.conv-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conv-item-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-status {
  font-size: 10.5px;
  color: #059669;
  flex: 0 0 auto;
}

.conv-status.CLOSED {
  color: #94a3b8;
}

.conv-item-preview {
  font-size: 12px;
  color: var(--muted);
  margin-top: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-item-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #94a3b8;
  margin-top: 6px;
}

.chat-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

.chat-title {
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-meta {
  display: flex;
  gap: 14px;
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 4px;
}

.chat-body {
  flex: 1;
  overflow: auto;
  padding: 22px 26px;
  background:
    radial-gradient(900px 300px at 50% 0%, #effcf9 0%, transparent 60%),
    #f8fafc;
}

.welcome {
  max-width: 620px;
  margin: 8vh auto 0;
  text-align: center;
  animation: rise 0.3s ease both;
}

.welcome-logo {
  width: 62px;
  height: 62px;
  border-radius: 18px;
  margin: 0 auto 18px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0ea5e9, #14b8a6);
  box-shadow: 0 18px 40px -16px rgba(20, 184, 166, 0.75);
}

.welcome h2 {
  font-size: 24px;
  letter-spacing: -0.02em;
  margin: 0 0 10px;
}

.welcome p {
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.8;
  margin: 0 auto 26px;
  max-width: 520px;
}

.suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  text-align: left;
}

.suggestion {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 13px 14px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  font-size: 12.5px;
  color: #334155;
  cursor: pointer;
  transition: 0.16s ease;
  line-height: 1.5;
}

.suggestion:hover {
  border-color: #99f6e4;
  background: #f0fdfa;
  transform: translateY(-1px);
  box-shadow: 0 10px 24px -18px rgba(13, 148, 136, 0.6);
}

.suggestion .el-icon {
  color: var(--brand);
  margin-top: 1px;
  flex: 0 0 auto;
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  align-items: flex-start;
  animation: rise 0.2s ease both;
}

.msg.user {
  justify-content: flex-end;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  flex: 0 0 32px;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
}

.assistant-avatar {
  background: linear-gradient(135deg, #0d9488, #0ea5e9);
}

.user-avatar {
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 50%;
}

.msg-main {
  max-width: min(760px, 78%);
  min-width: 0;
}

.msg.user .msg-main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.msg-bubble {
  border-radius: 14px;
  padding: 12px 15px;
}

.assistant-bubble {
  background: #fff;
  border: 1px solid var(--line);
  box-shadow: 0 8px 24px -22px rgba(15, 23, 42, 0.5);
}

.user-bubble {
  background: linear-gradient(135deg, #0d9488, #0f766e);
  color: #f0fdfa;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.loading-bubble {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--muted);
  font-size: 13px;
}

.dot-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
  animation: pulse 1s infinite ease-in-out;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.3;
    transform: scale(0.85);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}

.msg-time {
  font-size: 10.5px;
  color: #94a3b8;
  margin-top: 5px;
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed #eef2f7;
  font-size: 11px;
  color: #94a3b8;
}

.feedback {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.feedback-icon {
  cursor: pointer;
  padding: 3px;
  border-radius: 6px;
  color: #94a3b8;
  font-size: 14px;
}

.feedback-icon:hover {
  background: #f1f5f9;
  color: #475569;
}

.feedback-icon.active {
  color: var(--brand);
  background: var(--brand-soft);
}

.citations {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 11px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
}

.citations-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: #64748b;
  margin-bottom: 8px;
}

.citation-list {
  display: grid;
  gap: 6px;
}

.citation {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  background: #fff;
  border: 1px solid #e8edf5;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  transition: 0.15s ease;
}

.citation:hover {
  border-color: #99f6e4;
  background: #f0fdfa;
}

.cite-index {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  border-radius: 5px;
  background: var(--brand-soft);
  color: var(--brand-strong);
  font-size: 10.5px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

.cite-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
}

.cite-page {
  color: #94a3b8;
  font-size: 11px;
}

.cite-score {
  color: var(--brand);
  font-size: 11px;
  font-weight: 600;
}

.handoff-bar {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 11px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.handoff-text {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: #92400e;
}

.composer {
  border-top: 1px solid var(--line);
  padding: 12px 16px 14px;
  background: #fff;
}

.composer .el-textarea__inner {
  border-radius: 12px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  padding: 10px 12px;
  font-size: 13.5px;
  background: #fbfdff;
}

.composer .el-textarea__inner:focus {
  box-shadow: 0 0 0 1.5px var(--brand) inset;
  background: #fff;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 9px;
}

.composer-hint {
  font-size: 11px;
  color: #94a3b8;
}

.composer-closed {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px;
  color: var(--muted);
  font-size: 13px;
  background: #f8fafc;
  border-radius: 10px;
}

.citation-detail-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
  color: var(--brand);
}

.citation-doc {
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
}

.citation-snippet {
  margin-top: 12px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.8;
  color: #334155;
  white-space: pre-wrap;
}

.handoff-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 900px) {
  .conv-panel {
    display: none;
  }
  .suggestions {
    grid-template-columns: 1fr;
  }
}
</style>
