<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">
          <svg width="22" height="22" viewBox="0 0 64 64" fill="none">
            <path d="M16 22c0-3.3 2.7-6 6-6h20c3.3 0 6 2.7 6 6v13c0 3.3-2.7 6-6 6H31l-9 7v-7h-0c-3.3 0-6-2.7-6-6V22z" fill="#fff" opacity="0.92"/>
            <circle cx="25" cy="28" r="2.4" fill="#0d9488"/>
            <circle cx="32" cy="28" r="2.4" fill="#0d9488"/>
            <circle cx="39" cy="28" r="2.4" fill="#0d9488"/>
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-name">云枢 Copilot</div>
          <div class="brand-sub">智能客服 · 工单协作</div>
        </div>
      </div>

      <div class="nav-group-title">工作台</div>
      <div
        v-for="item in menus"
        :key="item.path"
        class="nav-item"
        :class="{ active: activeMenu === item.key }"
        @click="router.push(item.path)"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </div>

      <div class="sidebar-footer">
        <div><span class="dot"></span>{{ providerLabel }}</div>
        <div style="margin-top: 4px">{{ runtime.chatModel || '模型加载中' }}</div>
        <div style="margin-top: 2px; color: #64748b">向量 {{ runtime.embeddingDim || '-' }} 维</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar-title">
          {{ currentTitle }}
          <span class="sub">{{ subtitle }}</span>
        </div>
        <div class="flex gap-12">
          <div class="model-badge">
            <span class="pulse"></span>
            {{ providerLabel }} · {{ runtime.chatModel || '未配置' }}
          </div>
          <el-dropdown trigger="click" @command="onCommand">
            <div class="user-chip">
              <div class="avatar">{{ initial }}</div>
              <div style="line-height: 1.2">
                <div style="font-size: 13px; font-weight: 600">{{ auth.displayName }}</div>
                <span class="role-badge" :class="auth.role">{{ roleLabel[auth.role] }}</span>
              </div>
              <el-icon color="#94a3b8"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile" disabled>
                  {{ auth.user?.username }} · {{ roleLabel[auth.role] }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ChatDotRound,
  Collection,
  DataAnalysis,
  Setting,
  SwitchButton,
  Tickets,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { roleLabel } from '../utils/format'
import { runtimeInfo } from '../api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const runtime = ref({})
const providerLabel = computed(() => (runtime.value.provider === 'OLLAMA' ? '本地 Ollama' : '阿里云百炼'))

const allMenus = [
  { key: 'chat', label: '智能对话', path: '/chat', icon: ChatDotRound, roles: ['USER', 'AGENT', 'ADMIN'] },
  { key: 'tickets', label: '工单中心', path: '/tickets', icon: Tickets, roles: ['USER', 'AGENT', 'ADMIN'] },
  { key: 'knowledge', label: '企业知识库', path: '/knowledge', icon: Collection, roles: ['USER', 'AGENT', 'ADMIN'] },
  { key: 'dashboard', label: '运营看板', path: '/dashboard', icon: DataAnalysis, roles: ['AGENT', 'ADMIN'] },
  { key: 'settings', label: '系统设置', path: '/settings', icon: Setting, roles: ['ADMIN'] },
]

const menus = computed(() => allMenus.filter((item) => item.roles.includes(auth.role)))
const activeMenu = computed(() => route.meta.menu)
const currentTitle = computed(() => route.meta.title || '工作台')
const initial = computed(() => (auth.displayName || 'U').slice(0, 1).toUpperCase())

const subtitleMap = {
  chat: 'RAG 知识检索 · 引用溯源 · 转人工',
  tickets: auth.isAgent ? '状态机流转 · 乐观锁 · Copilot 辅助' : '提交问题 · 跟踪处理进度',
  knowledge: '文档解析 · 切片 · 向量索引',
  dashboard: '服务质量与 AI 调用洞察',
  settings: '模型提供方 · 权限与成员',
}
const subtitle = computed(() => subtitleMap[route.meta.menu] || '')

function onCommand(command) {
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

onMounted(async () => {
  try {
    runtime.value = await runtimeInfo()
  } catch {
    runtime.value = {}
  }
})
</script>
