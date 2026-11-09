<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">工单中心</h2>
        <div class="page-sub">
          {{ auth.isAgent ? '状态机流转 + 乐观锁并发控制，AI Copilot 辅助处理' : '提交与跟踪你的问题，处理进展实时可见' }}
        </div>
      </div>
      <div class="flex gap-8">
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="createVisible = true">新建工单</el-button>
      </div>
    </div>

    <div class="panel filters">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索工单编号 / 标题"
        clearable
        style="width: 230px"
        :prefix-icon="Search"
        @keyup.enter="applyFilters"
      />
      <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px">
        <el-option v-for="(meta, key) in statusMeta" :key="key" :label="meta.label" :value="key" />
      </el-select>
      <el-select v-model="filters.category" placeholder="全部分类" clearable style="width: 130px">
        <el-option v-for="(label, key) in categoryMeta" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="filters.priority" placeholder="全部优先级" clearable style="width: 130px">
        <el-option v-for="(meta, key) in priorityMeta" :key="key" :label="meta.label" :value="key" />
      </el-select>
      <el-checkbox v-if="auth.isAgent" v-model="filters.mine" label="只看我处理的" @change="applyFilters" />
      <div class="grow"></div>
      <el-button type="primary" plain @click="applyFilters">筛选</el-button>
    </div>

    <div class="panel" style="margin-top: 16px">
      <el-table :data="list" v-loading="loading" row-key="id" @row-click="openDetail" style="width: 100%">
        <el-table-column label="工单编号" width="170">
          <template #default="{ row }">
            <span class="mono ticket-no">{{ row.ticketNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="ticket-title">{{ row.title }}</div>
            <div class="ticket-preview">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <span class="soft-tag">{{ categoryMeta[row.category] || row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="priorityMeta[row.priority]?.type" effect="light" size="small">
              {{ priorityMeta[row.priority]?.label || row.priority }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status]?.type" effect="plain" size="small">
              {{ statusMeta[row.status]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAgent" label="提交人" width="110" prop="userName" />
        <el-table-column label="处理人" width="120">
          <template #default="{ row }">
            <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
            <span v-else class="muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="" width="70" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-hint">暂无工单，点击右上角「新建工单」提交问题</div>
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>

    <el-drawer v-model="drawerVisible" size="760px" :with-header="false" destroy-on-close>
      <div v-if="detail" class="drawer-body">
        <div class="detail-head">
          <div class="detail-head-top">
            <div>
              <div class="detail-no mono">{{ detail.ticket.ticketNo }}</div>
              <h3 class="detail-title">{{ detail.ticket.title }}</h3>
            </div>
            <el-tag :type="statusMeta[detail.ticket.status]?.type" effect="dark" size="large">
              {{ statusMeta[detail.ticket.status]?.label }}
            </el-tag>
          </div>
          <div class="detail-meta">
            <span><el-icon><User /></el-icon>{{ detail.ticket.userName }}</span>
            <span><el-icon><Service /></el-icon>{{ detail.ticket.assigneeName || '未分配' }}</span>
            <span><el-icon><FolderOpened /></el-icon>{{ categoryMeta[detail.ticket.category] }}</span>
            <span><el-icon><Flag /></el-icon>{{ priorityMeta[detail.ticket.priority]?.label }}优先级</span>
            <span><el-icon><Clock /></el-icon>{{ formatTime(detail.ticket.createTime) }}</span>
            <span v-if="detail.ticket.conversationId" class="link" @click="goConversation">
              <el-icon><ChatDotRound /></el-icon>查看关联会话
            </span>
          </div>
        </div>

        <div class="detail-section">
          <div class="section-label">问题描述</div>
          <div class="detail-desc">{{ detail.ticket.description }}</div>
        </div>

        <div class="detail-actions">
          <template v-if="isAssigneeOrAdmin">
            <el-button v-if="canAssign" size="small" :icon="UserFilled" @click="openAssign">分配工单</el-button>
            <el-button v-if="canStart" size="small" type="primary" :icon="VideoPlay" @click="doAction('start')">开始处理</el-button>
            <el-button v-if="canWait" size="small" :icon="Timer" @click="openAction('wait')">等待用户</el-button>
            <el-button v-if="canResolve" size="small" type="success" :icon="CircleCheck" @click="openAction('resolve')">标记解决</el-button>
          </template>
          <el-button v-if="canClose" size="small" type="info" :icon="CircleClose" @click="openAction('close')">关闭工单</el-button>
          <el-button v-if="canReopen" size="small" type="warning" :icon="RefreshLeft" @click="openAction('reopen')">重新打开</el-button>
          <div class="grow"></div>
          <span class="muted" style="font-size: 12px">版本 v{{ detail.ticket.version }} · 乐观锁保护</span>
        </div>

        <div v-if="canReply" class="reply-box">
          <el-input
            v-model="replyText"
            type="textarea"
            :rows="3"
            maxlength="2000"
            placeholder="输入回复内容，用户与客服都可以在此沟通..."
          />
          <div class="reply-bar">
            <span class="muted" style="font-size: 12px">回复会写入工单事件流，可追溯</span>
            <el-button type="primary" :loading="replying" @click="submitReply">发送回复</el-button>
          </div>
        </div>

        <div v-if="auth.isAgent" class="copilot-card">
          <div class="copilot-head">
            <div class="flex gap-8">
              <div class="copilot-logo"><el-icon><MagicStick /></el-icon></div>
              <div>
                <div class="copilot-title">AI Copilot 辅助</div>
                <div class="muted" style="font-size: 11.5px">基于工单与会话生成，结果需人工确认</div>
              </div>
            </div>
            <div class="flex gap-8">
              <el-button size="small" :loading="copilotLoading === 'summary'" @click="runCopilot('summary')">生成摘要</el-button>
              <el-button size="small" :loading="copilotLoading === 'classify'" @click="runCopilot('classify')">智能分类</el-button>
              <el-button size="small" :loading="copilotLoading === 'reply'" @click="runCopilot('reply')">回复建议</el-button>
            </div>
          </div>

          <div v-if="copilotResult" class="copilot-result">
            <div class="copilot-result-head">
              <span class="soft-tag teal">{{ copilotLabel }}</span>
              <span class="muted" style="font-size: 11.5px">
                {{ copilotResult.model }} · {{ copilotResult.latencyMs }}ms
              </span>
            </div>

            <template v-if="copilotResult.type === 'SUMMARY'">
              <MarkdownView :content="copilotResult.content" />
            </template>

            <template v-else-if="copilotResult.type === 'CLASSIFY'">
              <div class="classify-row">
                <div>
                  <div class="muted" style="font-size: 11.5px">推荐分类</div>
                  <span class="soft-tag blue">{{ categoryMeta[copilotResult.category] }}</span>
                </div>
                <div>
                  <div class="muted" style="font-size: 11.5px">推荐优先级</div>
                  <el-tag :type="priorityMeta[copilotResult.priority]?.type" size="small">
                    {{ priorityMeta[copilotResult.priority]?.label }}
                  </el-tag>
                </div>
                <el-button size="small" type="primary" plain :loading="applyingClassify" @click="applyClassify">
                  应用到工单
                </el-button>
              </div>
              <div class="classify-reason">{{ copilotResult.reason }}</div>
            </template>

            <template v-else>
              <div class="reply-suggestion">{{ copilotResult.content }}</div>
              <div v-if="copilotResult.citations?.length" class="copilot-cites">
                <span class="muted" style="font-size: 11.5px">参考来源：</span>
                <span v-for="(cite, i) in copilotResult.citations" :key="i" class="soft-tag">
                  {{ cite.documentName }}{{ cite.pageNumber ? ` · 第${cite.pageNumber}页` : '' }}
                </span>
              </div>
              <el-button size="small" type="primary" plain style="margin-top: 10px" @click="useSuggestion">
                填入回复框
              </el-button>
            </template>
          </div>
          <div v-else class="copilot-empty">
            点击上方按钮，让 AI 帮你总结问题、推荐分类或起草回复。
          </div>
        </div>

        <div class="detail-section">
          <div class="section-label">流转记录 · {{ detail.events.length }}</div>
          <el-timeline class="timeline">
            <el-timeline-item
              v-for="event in detail.events"
              :key="event.id"
              :timestamp="formatTime(event.createTime)"
              :color="eventColor(event.eventType)"
              placement="top"
            >
              <div class="event-card">
                <div class="event-top">
                  <span class="event-type">{{ eventLabel[event.eventType] || event.eventType }}</span>
                  <span class="event-operator">
                    {{ event.operatorName }}
                    <span class="role-badge" :class="event.operatorRole">{{ roleLabel[event.operatorRole] || '' }}</span>
                  </span>
                  <span v-if="event.oldStatus && event.newStatus && event.oldStatus !== event.newStatus" class="event-flow">
                    {{ statusMeta[event.oldStatus]?.label }} → {{ statusMeta[event.newStatus]?.label }}
                  </span>
                </div>
                <div v-if="event.content" class="event-content">{{ event.content }}</div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="createVisible" title="新建工单" width="560px">
      <el-form :model="createForm" label-position="top">
        <el-form-item label="标题">
          <el-input v-model="createForm.title" maxlength="200" show-word-limit placeholder="一句话概括问题" />
        </el-form-item>
        <div class="two-col">
          <el-form-item label="分类">
            <el-select v-model="createForm.category" style="width: 100%">
              <el-option v-for="(label, key) in categoryMeta" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="createForm.priority" style="width: 100%">
              <el-option v-for="(meta, key) in priorityMeta" :key="key" :label="meta.label" :value="key" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="问题描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="5"
            maxlength="5000"
            show-word-limit
            placeholder="请描述问题现象、发生时间、已尝试的操作"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">提交工单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignVisible" title="分配工单" width="420px">
      <el-select v-model="assignId" placeholder="选择处理人" style="width: 100%">
        <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.nickname}（${roleLabel[agent.role]}）`" :value="agent.id" />
      </el-select>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="submitAssign">确定分配</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="actionVisible" :title="actionTitle" width="520px">
      <el-input v-model="actionContent" type="textarea" :rows="4" maxlength="2000" :placeholder="actionPlaceholder" />
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="submitAction">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  CircleCheck,
  CircleClose,
  Clock,
  Flag,
  FolderOpened,
  MagicStick,
  Plus,
  Refresh,
  RefreshLeft,
  Search,
  Service,
  Timer,
  User,
  UserFilled,
  VideoPlay,
} from '@element-plus/icons-vue'
import MarkdownView from '../components/MarkdownView.vue'
import { useAuthStore } from '../stores/auth'
import {
  assignTicket,
  closeTicket,
  copilotClassify,
  copilotReply,
  copilotSummary,
  createTicket,
  getTicket,
  listAgents,
  pageTickets,
  reopenTicket,
  replyTicket,
  resolveTicket,
  startTicket,
  updateTicketAttributes,
  waitUserTicket,
} from '../api'
import {
  categoryMeta,
  eventLabel,
  formatTime,
  priorityMeta,
  roleLabel,
  statusMeta,
} from '../utils/format'

const auth = useAuthStore()
const router = useRouter()

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const agents = ref([])
const filters = reactive({ status: '', category: '', priority: '', keyword: '', mine: false })

const drawerVisible = ref(false)
const detail = ref(null)
const replyText = ref('')
const replying = ref(false)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', description: '', category: 'TECHNICAL', priority: 'MEDIUM' })

const assignVisible = ref(false)
const assignId = ref(null)
const assigning = ref(false)

const actionVisible = ref(false)
const actionType = ref('')
const actionContent = ref('')
const actionLoading = ref(false)

const copilotLoading = ref('')
const copilotResult = ref(null)
const applyingClassify = ref(false)

const copilotLabel = computed(() => ({ SUMMARY: '工单摘要', CLASSIFY: '分类建议', REPLY: '回复建议' }[copilotResult.value?.type] || ''))
const ticket = computed(() => detail.value?.ticket || null)
const isAssigneeOrAdmin = computed(() => {
  if (!ticket.value || !auth.isAgent) return false
  return auth.isAdmin || ticket.value.assigneeId === auth.userId || ticket.value.assigneeId == null
})
const canAssign = computed(() => auth.isAgent && ['NEW', 'ASSIGNED'].includes(ticket.value?.status))
const canStart = computed(() => auth.isAgent && ['NEW', 'ASSIGNED'].includes(ticket.value?.status))
const canWait = computed(() => auth.isAgent && ticket.value?.status === 'IN_PROGRESS')
const canResolve = computed(() => auth.isAgent && ['IN_PROGRESS', 'WAITING_USER'].includes(ticket.value?.status))
const canClose = computed(() => {
  if (!ticket.value) return false
  const isOwner = ticket.value.userId === auth.userId
  return ticket.value.status === 'RESOLVED' && (isOwner || auth.isAgent)
})
const canReopen = computed(() => {
  if (!ticket.value) return false
  const isOwner = ticket.value.userId === auth.userId
  return ['RESOLVED', 'CLOSED'].includes(ticket.value.status) && (isOwner || auth.isAgent)
})
const canReply = computed(() => {
  if (!ticket.value || ticket.value.status === 'CLOSED') return false
  if (auth.isAgent) return auth.isAdmin || ticket.value.assigneeId === auth.userId || ticket.value.assigneeId == null
  return ticket.value.userId === auth.userId
})

const actionMeta = {
  wait: { title: '标记等待用户', placeholder: '例如：请补充报错截图与发生时间' },
  resolve: { title: '标记已解决', placeholder: '填写解决方案或处理结果' },
  close: { title: '关闭工单', placeholder: '填写关闭说明（可选）' },
  reopen: { title: '重新打开工单', placeholder: '填写重新打开的原因（可选）' },
}
const actionTitle = computed(() => actionMeta[actionType.value]?.title || '')
const actionPlaceholder = computed(() => actionMeta[actionType.value]?.placeholder || '')

function eventColor(type) {
  return {
    CREATED: '#94a3b8',
    ASSIGNED: '#0ea5e9',
    STARTED: '#0d9488',
    REPLY: '#64748b',
    WAITING_USER: '#d97706',
    RESOLVED: '#059669',
    CLOSED: '#94a3b8',
    REOPENED: '#7c3aed',
    UPDATED: '#6366f1',
  }[type] || '#94a3b8'
}

async function load() {
  loading.value = true
  try {
    const data = await pageTickets({
      page: page.value,
      size: size.value,
      status: filters.status || undefined,
      category: filters.category || undefined,
      priority: filters.priority || undefined,
      keyword: filters.keyword || undefined,
      mine: filters.mine || undefined,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  load()
}

async function reloadAll() {
  await load()
  if (drawerVisible.value && ticket.value) {
    await refreshDetail(ticket.value.id)
  }
}

async function loadAgents() {
  if (!auth.isAgent) return
  try {
    agents.value = await listAgents()
  } catch {
    agents.value = []
  }
}

async function openDetail(row) {
  drawerVisible.value = true
  copilotResult.value = null
  replyText.value = ''
  await refreshDetail(row.id)
}

async function refreshDetail(id) {
  detail.value = await getTicket(id)
}

function goConversation() {
  router.push({ path: '/chat', query: { conversationId: ticket.value.conversationId } })
}

function openAssign() {
  assignId.value = ticket.value.assigneeId || auth.userId
  assignVisible.value = true
}

async function submitAssign() {
  if (!assignId.value) {
    ElMessage.warning('请选择处理人')
    return
  }
  assigning.value = true
  try {
    await assignTicket(ticket.value.id, { assigneeId: assignId.value })
    ElMessage.success('已分配')
    assignVisible.value = false
    await afterAction()
  } finally {
    assigning.value = false
  }
}

async function doAction(type) {
  if (type === 'start') {
    await startTicket(ticket.value.id)
    ElMessage.success('已开始处理')
    await afterAction()
  }
}

function openAction(type) {
  actionType.value = type
  actionContent.value =
    type === 'resolve'
      ? '已定位问题原因并完成处理，请确认是否恢复正常。'
      : ''
  actionVisible.value = true
}

async function submitAction() {
  actionLoading.value = true
  const payload = { content: actionContent.value }
  try {
    if (actionType.value === 'wait') await waitUserTicket(ticket.value.id, payload)
    if (actionType.value === 'resolve') await resolveTicket(ticket.value.id, payload)
    if (actionType.value === 'close') await closeTicket(ticket.value.id, payload)
    if (actionType.value === 'reopen') await reopenTicket(ticket.value.id, payload)
    ElMessage.success('操作成功')
    actionVisible.value = false
    await afterAction()
  } finally {
    actionLoading.value = false
  }
}

async function submitReply() {
  if (!replyText.value.trim()) {
    ElMessage.warning('请输入回复内容')
    return
  }
  replying.value = true
  try {
    await replyTicket(ticket.value.id, { content: replyText.value.trim() })
    replyText.value = ''
    ElMessage.success('回复已发送')
    await afterAction()
  } finally {
    replying.value = false
  }
}

async function submitCreate() {
  if (!createForm.title.trim() || !createForm.description.trim()) {
    ElMessage.warning('请填写标题和问题描述')
    return
  }
  creating.value = true
  try {
    const created = await createTicket({ ...createForm })
    ElMessage.success(`工单 ${created.ticketNo} 已创建`)
    createVisible.value = false
    Object.assign(createForm, { title: '', description: '', category: 'TECHNICAL', priority: 'MEDIUM' })
    await load()
    await openDetail({ id: created.id })
  } finally {
    creating.value = false
  }
}

async function afterAction() {
  await load()
  if (ticket.value) {
    await refreshDetail(ticket.value.id)
  }
}

async function runCopilot(type) {
  if (!ticket.value) return
  copilotLoading.value = type
  try {
    if (type === 'summary') copilotResult.value = await copilotSummary(ticket.value.id)
    if (type === 'classify') copilotResult.value = await copilotClassify(ticket.value.id)
    if (type === 'reply') copilotResult.value = await copilotReply(ticket.value.id)
  } finally {
    copilotLoading.value = ''
  }
}

async function applyClassify() {
  applyingClassify.value = true
  try {
    await updateTicketAttributes(ticket.value.id, {
      category: copilotResult.value.category,
      priority: copilotResult.value.priority,
    })
    ElMessage.success('已应用分类与优先级')
    await afterAction()
  } finally {
    applyingClassify.value = false
  }
}

function useSuggestion() {
  replyText.value = copilotResult.value.content
  ElMessage.success('已填入回复框，可编辑后发送')
}

onMounted(async () => {
  await Promise.all([load(), loadAgents()])
})
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  flex-wrap: wrap;
}

.ticket-no {
  font-size: 12px;
  color: #475569;
}

.ticket-title {
  font-weight: 600;
  color: var(--ink);
  font-size: 13.5px;
}

.ticket-preview {
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 520px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
}

.drawer-body {
  padding: 24px 26px 40px;
}

.detail-head {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.detail-head-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.detail-no {
  font-size: 12px;
  color: #94a3b8;
}

.detail-title {
  font-size: 21px;
  margin: 6px 0 0;
  letter-spacing: -0.02em;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 14px;
  font-size: 12.5px;
  color: #64748b;
}

.detail-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.detail-meta .link {
  color: var(--brand);
  cursor: pointer;
}

.detail-section {
  margin-top: 20px;
}

.section-label {
  font-size: 12px;
  font-weight: 650;
  color: #475569;
  margin-bottom: 9px;
  letter-spacing: 0.02em;
}

.detail-desc {
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px 16px;
  font-size: 13.5px;
  line-height: 1.8;
  color: #334155;
  white-space: pre-wrap;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 18px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.reply-box {
  margin-top: 16px;
}

.reply-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.copilot-card {
  margin-top: 20px;
  border: 1px solid #c9ece7;
  background: linear-gradient(180deg, #f3fdfb, #fbfeff);
  border-radius: 14px;
  overflow: hidden;
}

.copilot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid #d7f2ed;
}

.copilot-logo {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: linear-gradient(135deg, #0d9488, #0ea5e9);
  color: #fff;
  display: grid;
  place-items: center;
}

.copilot-title {
  font-size: 13.5px;
  font-weight: 650;
}

.copilot-result {
  padding: 14px 16px 16px;
}

.copilot-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.copilot-empty {
  padding: 22px;
  text-align: center;
  color: #7c8da8;
  font-size: 12.5px;
}

.classify-row {
  display: flex;
  align-items: flex-end;
  gap: 26px;
  padding: 12px 14px;
  background: #fff;
  border-radius: 11px;
  border: 1px solid var(--line);
}

.classify-reason {
  margin-top: 10px;
  font-size: 12.5px;
  color: #475569;
  line-height: 1.7;
}

.reply-suggestion {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 13px 15px;
  font-size: 13.5px;
  line-height: 1.85;
  color: #334155;
  white-space: pre-wrap;
}

.copilot-cites {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}

.timeline {
  padding-left: 4px;
  margin-top: 4px;
}

.event-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 10px 12px;
}

.event-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.event-type {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.event-operator {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: #64748b;
}

.event-flow {
  font-size: 11px;
  color: #94a3b8;
  background: #f1f5f9;
  padding: 2px 7px;
  border-radius: 999px;
}

.event-content {
  margin-top: 7px;
  font-size: 12.5px;
  color: #475569;
  line-height: 1.7;
  white-space: pre-wrap;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
</style>
