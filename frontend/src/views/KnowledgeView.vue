<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">企业知识库</h2>
        <div class="page-sub">上传文档 → 解析切片 → Embedding → 向量检索，权限隔离在检索阶段生效</div>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreateKb">新建知识库</el-button>
    </div>

    <div class="kb-grid">
      <div
        v-for="kb in knowledgeBases"
        :key="kb.id"
        class="kb-card panel"
        :class="{ active: kb.id === activeKbId }"
        @click="selectKb(kb.id)"
      >
        <div class="kb-card-top">
          <div class="kb-icon">
            <el-icon><Collection /></el-icon>
          </div>
          <el-tag size="small" :type="kb.visibility === 'PUBLIC' ? 'success' : kb.visibility === 'INTERNAL' ? 'primary' : 'info'" effect="plain">
            {{ visibilityLabel[kb.visibility] }}
          </el-tag>
        </div>
        <div class="kb-name">{{ kb.name }}</div>
        <div class="kb-desc">{{ kb.description || '暂无描述' }}</div>
        <div class="kb-stats">
          <span><strong>{{ kb.documentCount }}</strong> 文档</span>
          <span><strong>{{ kb.chunkCount }}</strong> 切片</span>
          <span class="grow"></span>
          <span class="muted">{{ kb.ownerName }}</span>
        </div>
      </div>
      <div v-if="!knowledgeBases.length" class="panel empty-hint" style="grid-column: 1 / -1">
        暂无可访问的知识库。管理员可在右上角创建知识库并上传企业文档。
      </div>
    </div>

    <div class="panel doc-panel">
      <div class="panel-head">
        <div>
          <h3>{{ activeKb?.name || '文档列表' }}</h3>
          <div class="muted" style="font-size: 12px; margin-top: 3px">
            {{ activeKb ? `${activeKb.documentCount} 篇文档 · ${activeKb.chunkCount} 个切片 · ${visibilityLabel[activeKb.visibility]}` : '选择上方知识库查看文档' }}
          </div>
        </div>
        <div class="flex gap-8">
          <el-input
            v-model="docKeyword"
            placeholder="搜索文档名"
            clearable
            size="default"
            style="width: 200px"
            :prefix-icon="Search"
            @keyup.enter="loadDocs"
          />
          <el-button :icon="Refresh" @click="loadDocs">刷新</el-button>
          <el-button
            v-if="activeKb && canManage(activeKb)"
            type="primary"
            :icon="Upload"
            :disabled="!activeKb"
            @click="uploadVisible = true"
          >
            上传文档
          </el-button>
        </div>
      </div>

      <el-table :data="documents" v-loading="loadingDocs" style="width: 100%">
        <el-table-column label="文档" min-width="280">
          <template #default="{ row }">
            <div class="doc-cell">
              <div class="file-chip" :class="row.fileType">{{ fileIcon(row.fileType) }}</div>
              <div style="min-width: 0">
                <div class="doc-name">{{ row.documentName }}</div>
                <div class="doc-meta">{{ formatSize(row.fileSize) }} · {{ row.knowledgeBaseName }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <div class="flex gap-8">
              <el-tag :type="docStatusMeta[row.status]?.type" size="small" effect="light">
                {{ docStatusMeta[row.status]?.label || row.status }}
              </el-tag>
              <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMessage || '处理失败'" placement="top">
                <el-icon color="#e11d48"><Warning /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="切片" width="80">
          <template #default="{ row }">
            <span class="mono">{{ row.chunkCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="向量" width="200">
          <template #default="{ row }">
            <div v-if="row.embeddingModel" class="vector-info">
              <span class="mono">{{ row.embeddingModel }}</span>
              <span class="muted">{{ row.embeddingDim }} 维</span>
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="viewChunks(row)">切片</el-button>
            <el-button
              v-if="activeKb && canManage(activeKb)"
              text
              type="warning"
              size="small"
              :disabled="row.status === 'PROCESSING'"
              @click="reprocess(row)"
            >
              重新处理
            </el-button>
            <el-button v-if="activeKb && canManage(activeKb)" text type="danger" size="small" @click="removeDoc(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-hint">该知识库还没有文档，上传 PDF / Word / TXT / Markdown 后自动切片并向量化</div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="docPage"
          v-model:page-size="docSize"
          :total="docTotal"
          layout="total, prev, pager, next"
          background
          @current-change="loadDocs"
        />
      </div>
    </div>

    <el-dialog v-model="uploadVisible" title="上传知识文档" width="540px">
      <div class="upload-tip">
        支持 PDF、Word（doc/docx）、TXT、Markdown，单文件不超过 50MB。上传后由后台异步解析、切片并调用 Embedding 模型生成向量。
      </div>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".pdf,.doc,.docx,.txt,.md,.markdown"
        :on-change="onFileChange"
        :on-remove="() => (uploadFile = null)"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">文档会写入「{{ activeKb?.name }}」，向量维度取决于当前 Embedding 配置</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">开始上传并处理</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createKbVisible" title="新建知识库" width="480px">
      <el-form :model="createKbForm" label-position="top">
        <el-form-item label="名称">
          <el-input v-model="createKbForm.name" maxlength="128" placeholder="例如：IT 故障处理手册" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createKbForm.description" type="textarea" :rows="3" maxlength="500" placeholder="知识库包含哪些内容" />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-select v-model="createKbForm.visibility" style="width: 100%">
            <el-option label="私有（仅创建人 + 管理员）" value="PRIVATE" />
            <el-option label="内部（客服与管理员）" value="INTERNAL" :disabled="!auth.isAgent" />
            <el-option label="全员可见" value="PUBLIC" :disabled="!auth.isAgent" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createKbVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingKb" @click="submitCreateKb">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="chunkVisible" :title="`切片预览 · ${chunkDoc?.documentName || ''}`" width="680px">
      <div class="chunk-list">
        <div v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
          <div class="chunk-head">
            <span class="chunk-index">#{{ chunk.chunkIndex + 1 }}</span>
            <span v-if="chunk.pageNumber" class="muted">第 {{ chunk.pageNumber }} 页</span>
            <span class="muted">{{ chunk.charCount }} 字</span>
          </div>
          <div class="chunk-content">{{ chunk.content }}</div>
        </div>
        <div v-if="!chunks.length" class="empty-hint">暂无切片</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Collection,
  Plus,
  Refresh,
  Search,
  Upload,
  UploadFilled,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import {
  createKnowledgeBase,
  deleteDocument,
  getDocumentChunks,
  listKnowledgeBases,
  pageDocuments,
  reprocessDocument,
  uploadDocument,
} from '../api'
import {
  docStatusMeta,
  fileIcon,
  formatSize,
  formatTime,
  visibilityLabel,
} from '../utils/format'

const auth = useAuthStore()
const knowledgeBases = ref([])
const activeKbId = ref(null)
const documents = ref([])
const docTotal = ref(0)
const docPage = ref(1)
const docSize = ref(10)
const docKeyword = ref('')
const loadingDocs = ref(false)

const uploadVisible = ref(false)
const uploadFile = ref(null)
const uploading = ref(false)

const createKbVisible = ref(false)
const creatingKb = ref(false)
const createKbForm = reactive({ name: '', description: '', visibility: 'PRIVATE' })

const chunkVisible = ref(false)
const chunkDoc = ref(null)
const chunks = ref([])

let pollTimer = null

const activeKb = computed(() => knowledgeBases.value.find((kb) => kb.id === activeKbId.value) || null)

function canManage(kb) {
  if (!kb) return false
  if (auth.isAdmin) return true
  if (kb.ownerId === auth.userId) return true
  return auth.isAgent && kb.visibility !== 'PRIVATE'
}

async function loadKbs() {
  knowledgeBases.value = await listKnowledgeBases()
  if (!activeKbId.value && knowledgeBases.value.length) {
    activeKbId.value = knowledgeBases.value[0].id
  }
  if (activeKbId.value) {
    await loadDocs()
  }
}

async function selectKb(id) {
  activeKbId.value = id
  docPage.value = 1
  await loadDocs()
}

async function loadDocs() {
  if (!activeKbId.value) return
  loadingDocs.value = true
  try {
    const data = await pageDocuments({
      page: docPage.value,
      size: docSize.value,
      knowledgeBaseId: activeKbId.value,
      keyword: docKeyword.value || undefined,
    })
    documents.value = data.list
    docTotal.value = data.total
    schedulePoll()
  } finally {
    loadingDocs.value = false
  }
}

function schedulePoll() {
  const processing = documents.value.some((doc) => ['UPLOADED', 'PROCESSING'].includes(doc.status))
  if (processing && !pollTimer) {
    pollTimer = setInterval(async () => {
      try {
        await loadDocs()
        const list = await listKnowledgeBases()
        knowledgeBases.value = list
        if (!documents.value.some((doc) => ['UPLOADED', 'PROCESSING'].includes(doc.status))) {
          clearInterval(pollTimer)
          pollTimer = null
        }
      } catch {
        clearInterval(pollTimer)
        pollTimer = null
      }
    }, 4000)
  }
}

function openCreateKb() {
  Object.assign(createKbForm, { name: '', description: '', visibility: auth.isAgent ? 'PUBLIC' : 'PRIVATE' })
  createKbVisible.value = true
}

async function submitCreateKb() {
  if (!createKbForm.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  creatingKb.value = true
  try {
    const kb = await createKnowledgeBase({ ...createKbForm })
    ElMessage.success('知识库已创建')
    createKbVisible.value = false
    await loadKbs()
    await selectKb(kb.id)
  } finally {
    creatingKb.value = false
  }
}

function onFileChange(file) {
  uploadFile.value = file.raw
}

async function submitUpload() {
  if (!uploadFile.value) {
    ElMessage.warning('请选择文件')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('knowledgeBaseId', activeKbId.value)
    formData.append('file', uploadFile.value)
    await uploadDocument(formData)
    ElMessage.success('上传成功，正在后台解析与向量化')
    uploadVisible.value = false
    uploadFile.value = null
    await loadDocs()
    await loadKbs()
  } finally {
    uploading.value = false
  }
}

async function spotlight(id) {
  activeKbId.value = id
  await loadDocs()
}

async function viewChunks(row) {
  chunkDoc.value = row
  chunks.value = await getDocumentChunks(row.id, { limit: 200 })
  chunkVisible.value = true
}

async function reprocess(row) {
  await ElMessageBox.confirm(
    `重新处理会递增处理版本并重建「${row.documentName}」的切片与向量，旧任务结果会被丢弃。确定继续？`,
    '重新处理文档',
    { confirmButtonText: '开始处理', cancelButtonText: '取消', type: 'warning' },
  )
  await reprocessDocument(row.id)
  ElMessage.success('已提交重新处理')
  await loadDocs()
}

async function removeDoc(row) {
  await ElMessageBox.confirm(
    `删除「${row.documentName}」会同步删除其切片与向量，且不可恢复。确定删除？`,
    '删除文档',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  )
  await deleteDocument(row.id)
  ElMessage.success('已删除')
  await loadDocs()
  await loadKbs()
}

onMounted(async () => {
  await loadKbs()
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.kb-card {
  padding: 16px 18px;
  cursor: pointer;
  transition: 0.18s ease;
  border: 1px solid var(--line);
}

.kb-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 18px 36px -28px rgba(15, 23, 42, 0.5);
}

.kb-card.active {
  border-color: #6ccbc1;
  background: linear-gradient(180deg, #f4fffd, #fff);
  box-shadow: 0 18px 36px -28px rgba(13, 148, 136, 0.55);
}

.kb-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand-soft);
  color: var(--brand-strong);
  display: grid;
  place-items: center;
  font-size: 18px;
}

.kb-name {
  font-size: 15px;
  font-weight: 700;
  margin-top: 12px;
}

.kb-desc {
  font-size: 12px;
  color: var(--muted);
  margin-top: 5px;
  line-height: 1.6;
  min-height: 38px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.kb-stats {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px dashed #e8edf5;
  font-size: 11.5px;
  color: var(--muted);
}

.kb-stats strong {
  color: var(--ink);
  font-size: 13px;
}

.doc-panel {
  overflow: hidden;
}

.doc-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-chip {
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  background: #eef2f7;
  color: #475569;
}

.file-chip.pdf {
  background: #fee2e2;
  color: #b91c1c;
}

.file-chip.doc,
.file-chip.docx {
  background: #dbeafe;
  color: #1d4ed8;
}

.file-chip.md {
  background: #e6f7f5;
  color: #0f766e;
}

.doc-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.vector-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
}

.upload-tip {
  background: #f0fdfa;
  border: 1px solid #c9ece7;
  color: #115e59;
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.7;
  margin-bottom: 14px;
}

.chunk-list {
  max-height: 60vh;
  overflow: auto;
  display: grid;
  gap: 10px;
  padding-right: 4px;
}

.chunk-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfdff;
  padding: 10px 12px;
}

.chunk-head {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 11.5px;
  color: var(--muted);
  margin-bottom: 6px;
}

.chunk-index {
  font-weight: 700;
  color: var(--brand-strong);
}

.chunk-content {
  font-size: 12.5px;
  line-height: 1.75;
  color: #334155;
  white-space: pre-wrap;
  max-height: 150px;
  overflow: auto;
}
</style>
