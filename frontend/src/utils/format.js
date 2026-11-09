import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export const roleLabel = {
  USER: '员工',
  AGENT: '客服',
  ADMIN: '管理员',
}

export const statusMeta = {
  NEW: { label: '新建', type: 'info', color: '#64748b' },
  ASSIGNED: { label: '已分配', type: 'primary', color: '#0284c7' },
  IN_PROGRESS: { label: '处理中', type: 'warning', color: '#d97706' },
  WAITING_USER: { label: '等待用户', type: 'warning', color: '#b45309' },
  RESOLVED: { label: '已解决', type: 'success', color: '#059669' },
  CLOSED: { label: '已关闭', type: 'info', color: '#94a3b8' },
}

export const categoryMeta = {
  TECHNICAL: '技术问题',
  ACCOUNT: '账号问题',
  AFTER_SALES: '售后问题',
  PRODUCT: '产品使用',
  OTHER: '其他',
}

export const priorityMeta = {
  LOW: { label: '低', type: 'info', color: '#64748b' },
  MEDIUM: { label: '中', type: 'primary', color: '#0284c7' },
  HIGH: { label: '高', type: 'warning', color: '#d97706' },
  URGENT: { label: '紧急', type: 'danger', color: '#dc2626' },
}

export const docStatusMeta = {
  UPLOADED: { label: '待处理', type: 'info' },
  PROCESSING: { label: '处理中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

export const eventLabel = {
  CREATED: '创建工单',
  ASSIGNED: '分配/接单',
  STARTED: '开始处理',
  REPLY: '回复',
  WAITING_USER: '等待用户',
  RESOLVED: '标记解决',
  CLOSED: '关闭工单',
  REOPENED: '重新打开',
  UPDATED: '更新信息',
}

export const visibilityLabel = {
  PUBLIC: '全员可见',
  INTERNAL: '内部可见',
  PRIVATE: '私有',
}

export const sceneLabel = {
  CHAT: '智能问答',
  SUMMARY: '工单摘要',
  CLASSIFY: '智能分类',
  REPLY: '回复建议',
}

export function formatTime(value) {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

export function fromNow(value) {
  if (!value) return '-'
  return dayjs(value).fromNow()
}

export function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

export function fileIcon(type) {
  if (type === 'pdf') return 'PDF'
  if (type === 'doc' || type === 'docx') return 'DOC'
  if (type === 'md') return 'MD'
  return 'TXT'
}
