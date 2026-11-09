<template>
  <div class="login-page">
    <section class="hero">
      <div class="hero-grid"></div>
      <div class="hero-content">
        <div class="hero-brand">
          <div class="hero-logo">
            <svg width="26" height="26" viewBox="0 0 64 64" fill="none">
              <path d="M16 22c0-3.3 2.7-6 6-6h20c3.3 0 6 2.7 6 6v13c0 3.3-2.7 6-6 6H31l-9 7v-7h-0c-3.3 0-6-2.7-6-6V22z" fill="#fff"/>
              <circle cx="25" cy="28" r="2.4" fill="#0d9488"/>
              <circle cx="32" cy="28" r="2.4" fill="#0d9488"/>
              <circle cx="39" cy="28" r="2.4" fill="#0d9488"/>
            </svg>
          </div>
          <div>
            <div class="hero-name">云枢 Copilot</div>
            <div class="hero-sub">Enterprise Support Intelligence</div>
          </div>
        </div>

        <h1 class="hero-title">让每一次支持，<br /><span>都有知识可依。</span></h1>
        <p class="hero-desc">
          基于 RAG 的企业智能客服与工单协作平台。AI 检索内部知识给出有引用来源的答案，
          知识不足时自动转人工，并用 Copilot 辅助客服摘要、分类与回复。
        </p>

        <div class="hero-features">
          <div class="feature">
            <div class="feature-icon">01</div>
            <div>
              <div class="feature-title">检索增强问答</div>
              <div class="feature-text">文档切片 + 向量检索 + 引用校验</div>
            </div>
          </div>
          <div class="feature">
            <div class="feature-icon">02</div>
            <div>
              <div class="feature-title">工单状态机</div>
              <div class="feature-text">乐观锁控制并发，全流程可追溯</div>
            </div>
          </div>
          <div class="feature">
            <div class="feature-icon">03</div>
            <div>
              <div class="feature-title">模型自由切换</div>
              <div class="feature-text">阿里云百炼 / 本地 Ollama 一键切换</div>
            </div>
          </div>
        </div>

        <div class="hero-stats">
          <div><strong>6</strong><span>示例知识文档</span></div>
          <div><strong>1024</strong><span>向量维度</span></div>
          <div><strong>100%</strong><span>引用后端校验</span></div>
        </div>
      </div>
      <div class="hero-orb orb-a"></div>
      <div class="hero-orb orb-b"></div>
    </section>

    <section class="auth-side">
      <div class="auth-card rise">
        <div class="auth-head">
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建员工账号' }}</h2>
          <p>{{ mode === 'login' ? '登录企业智能服务中心，开始你的对话' : '注册后默认拥有员工权限，可发起咨询与工单' }}</p>
        </div>

        <el-tabs v-model="mode" class="auth-tabs">
          <el-tab-pane label="登录" name="login" />
          <el-tab-pane label="注册" name="register" />
        </el-tabs>

        <el-form :model="form" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" placeholder="请输入用户名" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="昵称">
            <el-input v-model="form.nickname" size="large" placeholder="例如：王小明" />
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="邮箱（选填）">
            <el-input v-model="form.email" size="large" placeholder="name@company.com" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
              @keyup.enter="submit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="auth-submit" :loading="loading" @click="submit">
            {{ mode === 'login' ? '登录系统' : '注册并登录' }}
          </el-button>
        </el-form>

        <div class="demo-block">
          <div class="demo-title">演示账号（点击直接登录）</div>
          <div class="demo-list">
            <div v-for="account in demoAccounts" :key="account.username" class="demo-item" @click="quickLogin(account)">
              <span class="demo-name">{{ account.label }}</span>
              <span class="demo-cred mono">{{ account.username }} / {{ account.password }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '', email: '' })

const demoAccounts = [
  { label: '管理员', username: 'admin', password: 'admin123' },
  { label: '客服', username: 'agent', password: 'agent123' },
  { label: '员工', username: 'user', password: 'user123' },
]

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login({ username: form.username, password: form.password })
      ElMessage.success('登录成功')
    } else {
      await auth.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email: form.email,
      })
      ElMessage.success('注册成功，已自动登录')
    }
    router.push(route.query.redirect || '/chat')
  } finally {
    loading.value = false
  }
}

async function quickLogin(account) {
  form.username = account.username
  form.password = account.password
  mode.value = 'login'
  await submit()
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  background: #0b1220;
}

.hero {
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(900px 500px at 10% 0%, rgba(14, 165, 233, 0.22), transparent 60%),
    radial-gradient(800px 600px at 90% 100%, rgba(13, 148, 136, 0.25), transparent 55%),
    #0b1220;
  color: #e2e8f0;
  display: flex;
  align-items: center;
  padding: 64px 64px;
}

.hero-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.06) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(70% 70% at 50% 40%, #000 20%, transparent 100%);
}

.hero-content {
  position: relative;
  z-index: 2;
  max-width: 560px;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 46px;
}

.hero-logo {
  width: 46px;
  height: 46px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0ea5e9, #14b8a6);
  box-shadow: 0 14px 40px -14px rgba(20, 184, 166, 0.8);
}

.hero-name {
  font-size: 18px;
  font-weight: 700;
  color: #f8fafc;
}

.hero-sub {
  font-size: 11px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: #64748b;
  margin-top: 2px;
}

.hero-title {
  font-size: 46px;
  line-height: 1.18;
  margin: 0 0 20px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: #f8fafc;
}

.hero-title span {
  background: linear-gradient(90deg, #5eead4, #38bdf8);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero-desc {
  color: #94a3b8;
  font-size: 14.5px;
  line-height: 1.9;
  margin: 0 0 38px;
  max-width: 520px;
}

.hero-features {
  display: grid;
  gap: 14px;
  margin-bottom: 42px;
}

.feature {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 13px 16px;
  border-radius: 13px;
  background: rgba(148, 163, 184, 0.06);
  border: 1px solid rgba(148, 163, 184, 0.1);
  backdrop-filter: blur(6px);
}

.feature-icon {
  font-size: 11px;
  font-weight: 700;
  color: #5eead4;
  background: rgba(45, 212, 191, 0.12);
  border: 1px solid rgba(45, 212, 191, 0.25);
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  flex: 0 0 28px;
}

.feature-title {
  font-size: 13.5px;
  font-weight: 650;
  color: #e2e8f0;
}

.feature-text {
  font-size: 12px;
  color: #7c8da8;
  margin-top: 3px;
}

.hero-stats {
  display: flex;
  gap: 42px;
  border-top: 1px solid rgba(148, 163, 184, 0.12);
  padding-top: 24px;
}

.hero-stats strong {
  display: block;
  font-size: 26px;
  color: #f8fafc;
  letter-spacing: -0.02em;
}

.hero-stats span {
  font-size: 12px;
  color: #64748b;
}

.hero-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.5;
}

.orb-a {
  width: 300px;
  height: 300px;
  background: #0ea5e9;
  top: -80px;
  right: -60px;
}

.orb-b {
  width: 260px;
  height: 260px;
  background: #14b8a6;
  bottom: -80px;
  left: 30%;
}

.auth-side {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f4f7fb;
  padding: 40px;
}

.auth-card {
  width: min(420px, 100%);
}

.auth-head h2 {
  font-size: 27px;
  margin: 0 0 8px;
  letter-spacing: -0.02em;
}

.auth-head p {
  color: #64748b;
  font-size: 13.5px;
  margin: 0;
}

.auth-tabs {
  margin: 20px 0 4px;
}

.auth-submit {
  width: 100%;
  height: 46px;
  font-size: 15px;
  margin-top: 6px;
  border-radius: 11px;
  box-shadow: 0 14px 30px -16px rgba(13, 148, 136, 0.9);
}

.demo-block {
  margin-top: 26px;
  border-top: 1px dashed #dbe3ee;
  padding-top: 18px;
}

.demo-title {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 10px;
}

.demo-list {
  display: grid;
  gap: 8px;
}

.demo-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 12px;
  border-radius: 10px;
  border: 1px solid #e6ebf3;
  background: #fff;
  cursor: pointer;
  transition: 0.16s ease;
}

.demo-item:hover {
  border-color: #99f6e4;
  background: #f0fdfa;
  transform: translateY(-1px);
}

.demo-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}

.demo-cred {
  font-size: 11.5px;
  color: #94a3b8;
}

@media (max-width: 960px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .hero {
    display: none;
  }
}
</style>
