import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('copilot_token') || '',
    user: JSON.parse(localStorage.getItem('copilot_user') || 'null'),
  }),
  getters: {
    isLogin: (state) => !!state.token,
    role: (state) => state.user?.role || 'USER',
    isAdmin() {
      return this.role === 'ADMIN'
    },
    isAgent() {
      return this.role === 'AGENT' || this.role === 'ADMIN'
    },
    displayName: (state) => state.user?.nickname || state.user?.username || '访客',
    userId: (state) => state.user?.id,
  },
  actions: {
    persist() {
      localStorage.setItem('copilot_token', this.token)
      localStorage.setItem('copilot_user', JSON.stringify(this.user))
    },
    async login(payload) {
      const data = await http.post('/api/auth/login', payload)
      this.token = data.token
      this.user = data.user
      this.persist()
      return data
    },
    async register(payload) {
      const data = await http.post('/api/auth/register', payload)
      this.token = data.token
      this.user = data.user
      this.persist()
      return data
    },
    async fetchMe() {
      const data = await http.get('/api/auth/me')
      this.user = data
      this.persist()
      return data
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('copilot_token')
      localStorage.removeItem('copilot_user')
    },
  },
})
