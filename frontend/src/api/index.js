import http from './http'

// 认证
export const login = (payload) => http.post('/api/auth/login', payload)
export const register = (payload) => http.post('/api/auth/register', payload)
export const getMe = () => http.get('/api/auth/me')

// 会话
export const createConversation = (payload) => http.post('/api/conversations', payload)
export const pageConversations = (params) => http.get('/api/conversations/page', { params })
export const getConversation = (id) => http.get(`/api/conversations/${id}`)
export const getMessages = (id, params) => http.get(`/api/conversations/${id}/messages`, { params })
export const closeConversation = (id) => http.post(`/api/conversations/${id}/close`)

// AI
export const chat = (payload) => http.post('/api/ai/chat', payload)
export const feedback = (payload) => http.post('/api/ai/feedback', payload)
export const handoff = (payload) => http.post('/api/ai/handoff', payload)
export const runtimeInfo = () => http.get('/api/ai/runtime')

// 知识库
export const listKnowledgeBases = () => http.get('/api/knowledge-bases')
export const createKnowledgeBase = (payload) => http.post('/api/knowledge-bases', payload)
export const deleteKnowledgeBase = (id) => http.delete(`/api/knowledge-bases/${id}`)
export const pageDocuments = (params) => http.get('/api/documents/page', { params })
export const getDocument = (id) => http.get(`/api/documents/${id}`)
export const getDocumentChunks = (id, params) => http.get(`/api/documents/${id}/chunks`, { params })
export const reprocessDocument = (id) => http.post(`/api/documents/${id}/reprocess`)
export const reprocessAll = () => http.post('/api/documents/reprocess-all')
export const deleteDocument = (id) => http.delete(`/api/documents/${id}`)
export const uploadDocument = (formData) => http.post('/api/documents/upload', formData)

// 工单
export const createTicket = (payload) => http.post('/api/tickets', payload)
export const pageTickets = (params) => http.get('/api/tickets/page', { params })
export const getTicket = (id) => http.get(`/api/tickets/${id}`)
export const assignTicket = (id, payload) => http.post(`/api/tickets/${id}/assign`, payload)
export const startTicket = (id) => http.post(`/api/tickets/${id}/start`)
export const replyTicket = (id, payload) => http.post(`/api/tickets/${id}/reply`, payload)
export const waitUserTicket = (id, payload) => http.post(`/api/tickets/${id}/wait-user`, payload)
export const resolveTicket = (id, payload) => http.post(`/api/tickets/${id}/resolve`, payload)
export const closeTicket = (id, payload) => http.post(`/api/tickets/${id}/close`, payload)
export const reopenTicket = (id, payload) => http.post(`/api/tickets/${id}/reopen`, payload)
export const updateTicketAttributes = (id, payload) => http.post(`/api/tickets/${id}/attributes`, payload)
export const listAgents = () => http.get('/api/users/agents')

// Copilot
export const copilotSummary = (id) => http.post(`/api/copilot/tickets/${id}/summary`)
export const copilotClassify = (id) => http.post(`/api/copilot/tickets/${id}/classify`)
export const copilotReply = (id) => http.post(`/api/copilot/tickets/${id}/reply-suggestion`)

// 看板与设置
export const statsOverview = () => http.get('/api/admin/stats/overview')
export const pageUsers = (params) => http.get('/api/admin/users', { params })
export const updateUserRole = (id, payload) => http.put(`/api/admin/users/${id}/role`, payload)
export const updateUserStatus = (id, payload) => http.put(`/api/admin/users/${id}/status`, payload)
export const getAiSetting = () => http.get('/api/admin/ai-setting')
export const updateAiSetting = (payload) => http.put('/api/admin/ai-setting', payload)
export const testAiSetting = (payload) => http.post('/api/admin/ai-setting/test', payload)
