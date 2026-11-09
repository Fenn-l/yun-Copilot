<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">系统设置</h2>
        <div class="page-sub">运行时切换大模型提供方，管理企业成员与角色权限</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="settings-tabs">
      <el-tab-pane label="AI 模型配置" name="ai">
        <div class="ai-grid" v-loading="loadingSetting">
          <div class="panel panel-pad">
            <div class="flex" style="justify-content: space-between; margin-bottom: 6px">
              <div>
                <h3 style="margin: 0; font-size: 15px">模型提供方</h3>
                <div class="muted" style="font-size: 12px; margin-top: 4px">
                  切换后立即生效，无需重启。对话与 Embedding 共用同一提供方配置。
                </div>
              </div>
              <el-tag :type="form.provider === 'OLLAMA' ? 'success' : 'primary'" effect="dark">
                当前：{{ form.provider === 'OLLAMA' ? '本地 Ollama' : '阿里云百炼' }}
              </el-tag>
            </div>

            <div class="provider-grid">
              <div
                v-for="option in providerOptions"
                :key="option.value"
                class="provider-card"
                :class="{ active: form.provider === option.value }"
                @click="selectProvider(option.value)"
              >
                <div class="provider-logo" :class="option.value.toLowerCase()">
                  <el-icon><component :is="option.icon" /></el-icon>
                </div>
                <div>
                  <div class="provider-name">{{ option.label }}</div>
                  <div class="provider-desc">{{ option.desc }}</div>
                </div>
                <el-icon v-if="form.provider === option.value" class="provider-check"><CircleCheckFilled /></el-icon>
              </div>
            </div>

            <el-form :model="form" label-position="top" style="margin-top: 18px">
              <el-form-item label="Base URL">
                <el-input v-model="form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
              </el-form-item>
              <el-form-item label="API Key">
                <el-input
                  v-model="form.apiKey"
                  type="password"
                  show-password
                  placeholder="留空则沿用该提供方已保存的 Key"
                />
                <div class="form-tip">每个模型提供方独立保存 API Key，切换后无需重复输入；本地 Ollama 可填任意占位值。</div>
              </el-form-item>
              <div class="form-row">
                <el-form-item label="对话模型">
                  <el-input v-model="form.chatModel" placeholder="qwen-plus / deepseek-r1:8b" />
                </el-form-item>
                <el-form-item label="Embedding 模型">
                  <el-input v-model="form.embeddingModel" placeholder="text-embedding-v4 / nomic-embed-text" />
                </el-form-item>
              </div>
              <div class="form-row three">
                <el-form-item label="向量维度">
                  <el-input-number v-model="form.embeddingDim" :min="64" :max="4096" :step="256" style="width: 100%" />
                </el-form-item>
                <el-form-item label="Temperature">
                  <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
                </el-form-item>
                <el-form-item label="最大输出 Token">
                  <el-input-number v-model="form.maxTokens" :min="64" :max="8192" :step="128" style="width: 100%" />
                </el-form-item>
              </div>
            </el-form>

            <div class="flex gap-8" style="margin-top: 6px">
              <el-button type="primary" :loading="saving" @click="saveSetting">保存配置</el-button>
              <el-button :loading="testing" @click="testConnection">测试连接</el-button>
              <el-button :icon="Refresh" :loading="rebuilding" @click="rebuildIndex">重建全部向量索引</el-button>
            </div>
          </div>

          <div class="side-col">
            <div class="panel panel-pad">
              <h3 style="margin: 0 0 10px; font-size: 15px">连接测试结果</h3>
              <div v-if="!testResult" class="empty-hint" style="padding: 24px 8px">
                点击「测试连接」验证对话与 Embedding 是否可用
              </div>
              <div v-else class="test-results">
                <div class="test-item" :class="testResult.chat?.ok ? 'ok' : 'fail'">
                  <div class="test-head">
                    <span>{{ testResult.chat?.ok ? '对话模型可用' : '对话模型异常' }}</span>
                    <span class="mono">{{ testResult.chat?.latencyMs }}ms</span>
                  </div>
                  <div class="test-body">
                    {{ testResult.chat?.ok ? `示例回复：${testResult.chat.sample}` : testResult.chat?.error }}
                  </div>
                </div>
                <div class="test-item" :class="testResult.embedding?.ok ? 'ok' : 'fail'">
                  <div class="test-head">
                    <span>{{ testResult.embedding?.ok ? 'Embedding 可用' : 'Embedding 异常' }}</span>
                    <span class="mono">{{ testResult.embedding?.latencyMs }}ms</span>
                  </div>
                  <div class="test-body">
                    {{ testResult.embedding?.ok ? `向量维度：${testResult.embedding.dimension}` : testResult.embedding?.error }}
                  </div>
                </div>
              </div>
            </div>

            <el-alert type="warning" :closable="false" show-icon style="margin-top: 14px">
              <template #title>切换 Embedding 模型后需要重建索引</template>
              向量维度与模型不一致的切片不会参与检索。切换后请点击「重建全部向量索引」，
              系统会重新解析切片并写入新向量。
            </el-alert>

            <div class="panel panel-pad" style="margin-top: 14px">
              <h3 style="margin: 0 0 10px; font-size: 15px">Ollama 本地准备</h3>
              <div class="mono code-block">ollama pull deepseek-r1:8b<br />ollama pull nomic-embed-text<br />ollama serve</div>
              <div class="muted" style="font-size: 12px; margin-top: 10px; line-height: 1.7">
                本地 Ollama 使用 OpenAI 兼容接口，Base URL 一般为
                <span class="mono">http://localhost:11434/v1</span>。百炼使用
                <span class="mono">/compatible-mode/v1</span>。
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="成员管理" name="users">
        <div class="panel">
          <div class="panel-head">
            <div class="flex gap-8">
              <el-input
                v-model="userKeyword"
                placeholder="搜索用户名 / 昵称"
                clearable
                style="width: 220px"
                :prefix-icon="Search"
                @keyup.enter="loadUsers"
              />
              <el-select v-model="userRole" placeholder="全部角色" clearable style="width: 130px" @change="loadUsers">
                <el-option label="员工" value="USER" />
                <el-option label="客服" value="AGENT" />
                <el-option label="管理员" value="ADMIN" />
              </el-select>
              <el-button @click="loadUsers">查询</el-button>
            </div>
            <span class="muted" style="font-size: 12px">角色变更对下一次请求立即生效</span>
          </div>
          <el-table :data="users" v-loading="loadingUsers" style="width: 100%">
            <el-table-column label="用户名" min-width="140">
              <template #default="{ row }">
                <div class="flex gap-8">
                  <div class="mini-avatar">{{ (row.nickname || row.username).slice(0, 1) }}</div>
                  <div>
                    <div style="font-weight: 600">{{ row.nickname }}</div>
                    <div class="muted mono" style="font-size: 11px">{{ row.username }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="邮箱" min-width="170" prop="email">
              <template #default="{ row }">{{ row.email || '-' }}</template>
            </el-table-column>
            <el-table-column label="角色" width="150">
              <template #default="{ row }">
                <el-select
                  :model-value="row.role"
                  size="small"
                  :disabled="row.id === auth.userId"
                  @change="(value) => changeRole(row, value)"
                >
                  <el-option label="员工" value="USER" />
                  <el-option label="客服" value="AGENT" />
                  <el-option label="管理员" value="ADMIN" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.status === 1"
                  :disabled="row.id === auth.userId"
                  @change="(value) => changeStatus(row, value)"
                />
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination
              v-model:current-page="userPage"
              v-model:page-size="userSize"
              :total="userTotal"
              layout="total, prev, pager, next"
              background
              @current-change="loadUsers"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheckFilled,
  Cloudy,
  Cpu,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import {
  getAiSetting,
  pageUsers,
  reprocessAll,
  testAiSetting,
  updateAiSetting,
  updateUserRole,
  updateUserStatus,
} from '../api'
import { formatTime, roleLabel } from '../utils/format'

const auth = useAuthStore()
const tab = ref('ai')
const loadingSetting = ref(false)
const setting = ref(null)
const form = reactive({
  provider: 'BAILIAN',
  baseUrl: '',
  apiKey: '',
  chatModel: '',
  embeddingModel: '',
  embeddingDim: 1024,
  temperature: 0.3,
  maxTokens: 1500,
})
const saving = ref(false)
const testing = ref(false)
const rebuilding = ref(false)
const testResult = ref(null)

const providerOptions = [
  { value: 'BAILIAN', label: '阿里云百炼', desc: 'DashScope OpenAI 兼容模式 · qwen-plus', icon: Cloudy },
  { value: 'OLLAMA', label: '本地 Ollama', desc: '本地模型 · deepseek-r1:8b / nomic-embed-text', icon: Cpu },
]

const users = ref([])
const userTotal = ref(0)
const userPage = ref(1)
const userSize = ref(10)
const userKeyword = ref('')
const userRole = ref('')
const loadingUsers = ref(false)

async function loadSetting() {
  loadingSetting.value = true
  try {
    const data = await getAiSetting()
    setting.value = data
    Object.assign(form, {
      provider: data.provider,
      baseUrl: data.baseUrl,
      apiKey: '',
      chatModel: data.chatModel,
      embeddingModel: data.embeddingModel,
      embeddingDim: data.embeddingDim,
      temperature: data.temperature,
      maxTokens: data.maxTokens,
    })
  } finally {
    loadingSetting.value = false
  }
}

function selectProvider(provider) {
  if (form.provider === provider) return
  form.provider = provider
  const preset = setting.value?.presets?.[provider]
  if (preset) {
    form.baseUrl = preset.baseUrl
    form.chatModel = preset.chatModel
    form.embeddingModel = preset.embeddingModel
    form.embeddingDim = preset.embeddingDim
    if (preset.maxTokens) form.maxTokens = preset.maxTokens
  }
  // 保留当前 API Key：切换提供方时不强行覆盖，避免切回百炼时丢失 Key。
  // 本地 Ollama 不校验 Authorization，因此仍可正常调用。
  form.apiKey = ''
  testResult.value = null
}

async function saveSetting() {
  saving.value = true
  try {
    const data = await updateAiSetting({ ...form })
    setting.value = data
    form.apiKey = ''
    ElMessage.success('模型配置已保存并立即生效')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testAiSetting({ ...form })
  } finally {
    testing.value = false
  }
}

async function rebuildIndex() {
  await ElMessageBox.confirm(
    '将重新处理全部文档并写入当前 Embedding 模型对应的向量，文档处理期间暂不可检索。确定继续？',
    '重建向量索引',
    { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' },
  )
  rebuilding.value = true
  try {
    const count = await reprocessAll()
    ElMessage.success(`已提交 ${count} 篇文档的重建任务`)
  } finally {
    rebuilding.value = false
  }
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    const data = await pageUsers({
      page: userPage.value,
      size: userSize.value,
      keyword: userKeyword.value || undefined,
      role: userRole.value || undefined,
    })
    users.value = data.list
    userTotal.value = data.total
  } finally {
    loadingUsers.value = false
  }
}

async function changeRole(row, role) {
  try {
    const updated = await updateUserRole(row.id, { role })
    row.role = updated.role
    ElMessage.success(`已将 ${row.username} 调整为${roleLabel[role]}`)
  } catch {
    /* 拦截器已提示 */
  }
}

async function changeStatus(row, enabled) {
  try {
    const updated = await updateUserStatus(row.id, { status: enabled ? 1 : 0 })
    row.status = updated.status
    ElMessage.success(enabled ? '账号已启用' : '账号已禁用')
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  await Promise.all([loadSetting(), loadUsers()])
})
</script>

<style scoped>
.settings-tabs {
  margin-top: 4px;
}

.ai-grid {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 16px;
  align-items: start;
}

.provider-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 14px;
}

.provider-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 13px;
  border: 1.5px solid var(--line);
  cursor: pointer;
  transition: 0.18s ease;
  position: relative;
}

.provider-card:hover {
  border-color: #a8e2db;
  background: #f7fffd;
}

.provider-card.active {
  border-color: #2dd4bf;
  background: linear-gradient(180deg, #f0fdfa, #fff);
  box-shadow: 0 12px 26px -22px rgba(13, 148, 136, 0.6);
}

.provider-logo {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 19px;
  flex: 0 0 40px;
  background: #eef2f7;
  color: #475569;
}

.provider-logo.bailian {
  background: #fff7ed;
  color: #c2410c;
}

.provider-logo.ollama {
  background: #ecfdf5;
  color: #047857;
}

.provider-name {
  font-size: 13.5px;
  font-weight: 650;
}

.provider-desc {
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 3px;
}

.provider-check {
  position: absolute;
  right: 12px;
  top: 12px;
  color: var(--brand);
  font-size: 17px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-row.three {
  grid-template-columns: repeat(3, 1fr);
}

.form-tip {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
  line-height: 1.6;
}

.side-col {
  display: flex;
  flex-direction: column;
}

.test-results {
  display: grid;
  gap: 10px;
}

.test-item {
  border-radius: 11px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  background: #f8fafc;
}

.test-item.ok {
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.test-item.fail {
  background: #fff1f2;
  border-color: #fecdd3;
}

.test-head {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}

.test-body {
  font-size: 12px;
  color: #64748b;
  margin-top: 5px;
  line-height: 1.65;
  word-break: break-all;
}

.code-block {
  background: #0b1220;
  color: #a7f3d0;
  border-radius: 10px;
  padding: 11px 13px;
  font-size: 11.5px;
  line-height: 1.9;
}

.mini-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0ea5e9, #0d9488);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
}

@media (max-width: 1200px) {
  .ai-grid {
    grid-template-columns: 1fr;
  }
}
</style>
