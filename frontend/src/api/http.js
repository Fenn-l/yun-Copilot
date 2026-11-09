import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 180000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('copilot_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data?.data,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '请求失败'
    if (status === 401) {
      localStorage.removeItem('copilot_token')
      localStorage.removeItem('copilot_user')
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login'
      }
      ElMessage.warning('登录已过期，请重新登录')
    } else if (status === 403) {
      ElMessage.error(message || '没有权限执行该操作')
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

export default http
