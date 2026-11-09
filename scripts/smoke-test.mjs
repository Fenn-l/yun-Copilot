// 端到端冒烟测试：node scripts/smoke-test.mjs
// 覆盖：登录、知识库、RAG 问答与引用、知识不足转人工、工单流转、Copilot、权限与统计

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080';

let passed = 0;
let failed = 0;

function check(name, condition, extra) {
  if (condition) {
    passed++;
    console.log(`  [PASS] ${name}`);
  } else {
    failed++;
    console.log(`  [FAIL] ${name}${extra ? ' -> ' + JSON.stringify(extra) : ''}`);
  }
}

async function api(method, path, { token, body, expect } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }
  if (expect && res.status !== expect) {
    throw new Error(`${method} ${path} expected ${expect} got ${res.status}: ${text.slice(0, 300)}`);
  }
  return { status: res.status, json };
}

async function login(username, password) {
  const res = await api('POST', '/api/auth/login', { body: { username, password } });
  if (res.status !== 200 || res.json.code !== 0) {
    throw new Error(`登录失败 ${username}: ${JSON.stringify(res.json)}`);
  }
  return res.json.data;
}

async function main() {
  console.log(`\n=== 企业智能客服 Copilot 冒烟测试 ${BASE} ===\n`);

  console.log('1. 认证');
  const admin = await login('admin', 'admin123');
  const agent = await login('agent', 'agent123');
  const user = await login('user', 'user123');
  check('admin 登录并返回 JWT', !!admin.token && admin.user.role === 'ADMIN');
  check('agent 登录', agent.user.role === 'AGENT');
  check('user 登录', user.user.role === 'USER');
  const me = await api('GET', '/api/auth/me', { token: user.token, expect: 200 });
  check('获取当前用户', me.json.data.username === 'user');
  const unauth = await api('GET', '/api/conversations/page');
  check('未登录访问被拒绝(401)', unauth.status === 401);

  console.log('\n2. 知识库权限与文档');
  const userKb = await api('GET', '/api/knowledge-bases', { token: user.token, expect: 200 });
  const agentKb = await api('GET', '/api/knowledge-bases', { token: agent.token, expect: 200 });
  check('员工可以看到公开知识库', userKb.json.data.length === 3);
  check('客服可以看到公开知识库', agentKb.json.data.length === 3);
  const docs = await api('GET', '/api/documents/page?size=20', { token: admin.token, expect: 200 });
  check('示例文档全部进入知识库', docs.json.data.total === 6, { total: docs.json.data.total });
  check('文档处理状态为 COMPLETED',
    docs.json.data.list.every(d => d.status === 'COMPLETED'),
    docs.json.data.list.map(d => d.status));
  const docId = docs.json.data.list[0].id;
  const chunks = await api('GET', `/api/documents/${docId}/chunks?limit=5`, { token: agent.token, expect: 200 });
  check('可查看文档切片', chunks.json.data.length > 0);

  console.log('\n3. AI 智能问答（RAG）');
  const conv = await api('POST', '/api/conversations', { token: user.token, body: { title: '邮箱问题咨询' }, expect: 200 });
  const conversationId = conv.json.data.id;
  const chat1 = await api('POST', '/api/ai/chat', {
    token: user.token,
    body: { conversationId, message: '公司邮箱登录失败，提示账号认证异常，应该怎么处理？' },
    expect: 200,
  });
  const answer1 = chat1.json.data;
  console.log('  回答:', answer1.answer.replace(/\n/g, ' ').slice(0, 160), '...');
  check('返回 AI 回答', typeof answer1.answer === 'string' && answer1.answer.length > 20);
  check('返回引用来源', Array.isArray(answer1.citations) && answer1.citations.length > 0,
    answer1.citations);
  check('引用包含真实文档名', answer1.citations.every(c => c.documentName && c.documentId));
  check('回答未误判为知识不足', answer1.handoffRequired === false, answer1);

  const chat2 = await api('POST', '/api/ai/chat', {
    token: user.token,
    body: { conversationId, message: '那忘记原密码怎么办？需要提前几天申请请假？' },
    expect: 200,
  });
  console.log('  追问回答:', chat2.json.data.answer.replace(/\n/g, ' ').slice(0, 160), '...');
  check('多轮上下文对话返回回答', chat2.json.data.answer.length > 10);

  const chat3 = await api('POST', '/api/ai/chat', {
    token: user.token,
    body: { conversationId, message: '请告诉我公司股票明天会涨吗？' },
    expect: 200,
  });
  console.log('  知识不足回答:', chat3.json.data.answer.replace(/\n/g, ' ').slice(0, 120));
  check('知识不足时转人工标记', chat3.json.data.handoffRequired === true, chat3.json.data);
  check('知识不足原因正确', chat3.json.data.reason === 'INSUFFICIENT_KNOWLEDGE', chat3.json.data.reason);
  check('知识不足不返回引用', (chat3.json.data.citations || []).length === 0);

  const history = await api('GET', `/api/conversations/${conversationId}/messages`, { token: user.token, expect: 200 });
  check('会话历史已落库', history.json.data.length >= 6, { size: history.json.data.length });
  const assistantMsg = history.json.data.filter(m => m.role === 'ASSISTANT').pop();
  const feedback = await api('POST', '/api/ai/feedback', {
    token: user.token,
    body: { messageId: assistantMsg.id, rating: 1, comment: '回答准确' },
    expect: 200,
  });
  check('AI 答案评价成功', feedback.json.code === 0);

  console.log('\n4. 转人工与工单流转');
  const handoff = await api('POST', '/api/ai/handoff', {
    token: user.token,
    body: { conversationId, title: '邮箱账号认证异常（AI 转人工）', category: 'ACCOUNT', priority: 'HIGH' },
    expect: 200,
  });
  const ticket = handoff.json.data;
  console.log('  工单:', ticket.ticketNo, ticket.status, ticket.category, ticket.priority);
  check('AI 会话转人工创建工单', ticket.ticketNo && ticket.status === 'NEW');
  check('工单关联会话', ticket.conversationId === conversationId);

  const assign = await api('POST', `/api/tickets/${ticket.id}/assign`, {
    token: agent.token, body: { assigneeId: agent.user.id }, expect: 200,
  });
  check('分配工单 NEW -> ASSIGNED', assign.json.data.status === 'ASSIGNED');
  const start = await api('POST', `/api/tickets/${ticket.id}/start`, { token: agent.token, expect: 200 });
  check('开始处理 ASSIGNED -> IN_PROGRESS', start.json.data.status === 'IN_PROGRESS');
  const illegalClose = await api('POST', `/api/tickets/${ticket.id}/close`, {
    token: agent.token, body: { content: '尝试直接关闭' },
  });
  check('非法状态流转被拒绝(409)', illegalClose.status === 409, illegalClose.json);
  const reply = await api('POST', `/api/tickets/${ticket.id}/reply`, {
    token: agent.token, body: { content: '正在核查您的邮箱账号，请稍候。' }, expect: 200,
  });
  check('客服回复工单', reply.json.code === 0);
  const resolve = await api('POST', `/api/tickets/${ticket.id}/resolve`, {
    token: agent.token, body: { content: '已重置账号认证配置并通知用户。' }, expect: 200,
  });
  check('标记解决 IN_PROGRESS -> RESOLVED', resolve.json.data.status === 'RESOLVED');
  const close = await api('POST', `/api/tickets/${ticket.id}/close`, {
    token: user.token, body: { content: '问题已解决，确认关闭' }, expect: 200,
  });
  check('用户确认关闭 RESOLVED -> CLOSED', close.json.data.status === 'CLOSED');

  const userDenied = await api('GET', '/api/admin/stats/overview', { token: user.token });
  check('普通用户访问运营看板被拒绝(403)', userDenied.status === 403);
  const userTickets = await api('GET', '/api/tickets/page?size=20', { token: user.token, expect: 200 });
  check('普通用户只看到自己的工单',
    userTickets.json.data.list.every(t => t.userId === user.user.id));

  console.log('\n5. AI Copilot（客服辅助）');
  const copilotTicket = 2;
  const summary = await api('POST', `/api/copilot/tickets/${copilotTicket}/summary`, { token: agent.token, expect: 200 });
  console.log('  摘要:', summary.json.data.content.replace(/\n/g, ' ').slice(0, 140), '...');
  check('生成工单摘要', summary.json.data.type === 'SUMMARY' && summary.json.data.content.length > 30);

  const classify = await api('POST', `/api/copilot/tickets/${copilotTicket}/classify`, { token: agent.token, expect: 200 });
  console.log('  分类建议:', classify.json.data.category, classify.json.data.priority, '|', classify.json.data.reason);
  check('工单分类建议合法', ['TECHNICAL', 'ACCOUNT', 'AFTER_SALES', 'PRODUCT', 'OTHER'].includes(classify.json.data.category));
  check('优先级建议合法', ['LOW', 'MEDIUM', 'HIGH', 'URGENT'].includes(classify.json.data.priority));

  const replySuggestion = await api('POST', `/api/copilot/tickets/${copilotTicket}/reply-suggestion`, {
    token: agent.token, expect: 200,
  });
  console.log('  回复建议:', replySuggestion.json.data.content.replace(/\n/g, ' ').slice(0, 140), '...');
  check('生成回复建议', replySuggestion.json.data.content.length > 20);
  check('回复建议附带知识来源', (replySuggestion.json.data.citations || []).length > 0);

  const userCopilot = await api('POST', `/api/copilot/tickets/${copilotTicket}/summary`, { token: user.token });
  check('普通用户不能调用 Copilot(403)', userCopilot.status === 403);

  console.log('\n6. 运营看板与 AI 配置');
  const stats = await api('GET', '/api/admin/stats/overview', { token: agent.token, expect: 200 });
  check('运营看板返回工单统计', typeof stats.json.data.totalTickets === 'number');
  check('运营看板返回 AI 指标', typeof stats.json.data.aiRunsToday === 'number');
  check('运营看板返回趋势数据', Array.isArray(stats.json.data.ticketTrend));

  const setting = await api('GET', '/api/admin/ai-setting', { token: admin.token, expect: 200 });
  check('AI 设置可读取且 Key 已脱敏',
    setting.json.data.provider === 'BAILIAN' && setting.json.data.apiKeyMasked.includes('****'),
    setting.json.data.apiKeyMasked);
  const userSetting = await api('GET', '/api/admin/ai-setting', { token: user.token });
  check('普通用户不能查看 AI 设置(403)', userSetting.status === 403);
  const runtime = await api('GET', '/api/ai/runtime', { token: user.token, expect: 200 });
  check('运行时模型信息可读取', !!runtime.json.data.chatModel);

  console.log(`\n=== 结果：通过 ${passed} 项，失败 ${failed} 项 ===\n`);
  if (failed > 0) process.exit(1);
}

main().catch(err => {
  console.error('\n冒烟测试异常:', err.message);
  process.exit(1);
});
