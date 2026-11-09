<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">运营看板</h2>
        <div class="page-sub">工单流转、AI 调用质量与知识库规模的整体视图</div>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
    </div>

    <div class="stat-grid" v-loading="loading">
      <div class="stat-card panel">
        <div class="stat-icon teal"><el-icon><Tickets /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">工单总数</div>
          <div class="stat-value">{{ stats.totalTickets ?? '-' }}</div>
          <div class="stat-foot">待处理 {{ stats.openTickets ?? 0 }} · 今日解决 {{ stats.resolvedToday ?? 0 }}</div>
        </div>
      </div>
      <div class="stat-card panel">
        <div class="stat-icon amber"><el-icon><Bell /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">未分配工单</div>
          <div class="stat-value">{{ stats.unassignedTickets ?? '-' }}</div>
          <div class="stat-foot">今日关闭 {{ stats.closedToday ?? 0 }} 单</div>
        </div>
      </div>
      <div class="stat-card panel">
        <div class="stat-icon blue"><el-icon><MagicStick /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">今日 AI 调用</div>
          <div class="stat-value">{{ stats.aiRunsToday ?? '-' }}</div>
          <div class="stat-foot">成功率 {{ stats.aiSuccessRate == null ? '-' : stats.aiSuccessRate + '%' }}</div>
        </div>
      </div>
      <div class="stat-card panel">
        <div class="stat-icon indigo"><el-icon><Timer /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">平均响应耗时</div>
          <div class="stat-value">{{ stats.aiAvgLatency ?? '-' }}<span class="unit">ms</span></div>
          <div class="stat-foot">今日 Token 消耗 {{ stats.aiTokensToday ?? 0 }}</div>
        </div>
      </div>
      <div class="stat-card panel">
        <div class="stat-icon rose"><el-icon><Collection /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">知识切片</div>
          <div class="stat-value">{{ stats.chunks ?? '-' }}</div>
          <div class="stat-foot">{{ stats.documents ?? 0 }} 篇文档 · {{ stats.knowledgeBases ?? 0 }} 个知识库</div>
        </div>
      </div>
      <div class="stat-card panel">
        <div class="stat-icon green"><el-icon><ChatDotRound /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">进行中会话</div>
          <div class="stat-value">{{ stats.activeConversations ?? '-' }}</div>
          <div class="stat-foot">累计会话 {{ stats.totalConversations ?? 0 }}</div>
        </div>
      </div>
    </div>

    <div class="chart-grid">
      <div class="panel chart-card">
        <div class="panel-head">
          <h3>工单状态分布</h3>
          <span class="muted" style="font-size: 12px">全部工单</span>
        </div>
        <div ref="statusChartEl" class="chart"></div>
      </div>
      <div class="panel chart-card wide">
        <div class="panel-head">
          <h3>近 7 天趋势</h3>
          <span class="muted" style="font-size: 12px">新增工单 / AI 调用</span>
        </div>
        <div ref="trendChartEl" class="chart"></div>
      </div>
      <div class="panel chart-card">
        <div class="panel-head">
          <h3>工单分类分布</h3>
          <span class="muted" style="font-size: 12px">按类别统计</span>
        </div>
        <div ref="categoryChartEl" class="chart"></div>
      </div>
    </div>

    <div class="bottom-grid">
      <div class="panel">
        <div class="panel-head">
          <h3>AI 场景统计（近 7 天）</h3>
          <span class="soft-tag teal">RAG / Copilot</span>
        </div>
        <el-table :data="stats.sceneStats || []" style="width: 100%">
          <el-table-column label="场景" min-width="120">
            <template #default="{ row }">{{ sceneLabel[row.scene] || row.scene }}</template>
          </el-table-column>
          <el-table-column label="调用次数" width="100">
            <template #default="{ row }"><span class="mono">{{ row.total }}</span></template>
          </el-table-column>
          <el-table-column label="平均耗时" width="110">
            <template #default="{ row }">{{ row.avg_latency ?? '-' }} ms</template>
          </el-table-column>
          <el-table-column label="Token" width="100">
            <template #default="{ row }"><span class="mono">{{ row.tokens || 0 }}</span></template>
          </el-table-column>
          <template #empty><div class="empty-hint">暂无 AI 调用记录</div></template>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h3>最新工单</h3>
          <el-button text type="primary" size="small" @click="$router.push('/tickets')">查看全部</el-button>
        </div>
        <div class="recent-list">
          <div
            v-for="ticket in stats.recentTickets || []"
            :key="ticket.id"
            class="recent-item"
            @click="$router.push('/tickets')"
          >
            <div class="recent-main">
              <div class="recent-title">{{ ticket.title }}</div>
              <div class="recent-meta">
                <span class="mono">{{ ticket.ticketNo }}</span>
                <span>{{ ticket.userName }}</span>
                <span>{{ formatTime(ticket.createTime) }}</span>
              </div>
            </div>
            <el-tag :type="statusMeta[ticket.status]?.type" effect="plain" size="small">
              {{ statusMeta[ticket.status]?.label }}
            </el-tag>
          </div>
          <div v-if="!(stats.recentTickets || []).length" class="empty-hint">暂无工单</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { Bell, ChatDotRound, Collection, MagicStick, Refresh, Tickets, Timer } from '@element-plus/icons-vue'
import { statsOverview } from '../api'
import { categoryMeta, formatTime, sceneLabel, statusMeta } from '../utils/format'

const stats = ref({})
const loading = ref(false)
const statusChartEl = ref(null)
const trendChartEl = ref(null)
const categoryChartEl = ref(null)
const charts = []

const palette = ['#0d9488', '#0ea5e9', '#8b5cf6', '#f59e0b', '#f43f5e', '#64748b']

async function load() {
  loading.value = true
  try {
    stats.value = await statsOverview()
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
}

function initChart(el) {
  if (!el) return null
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  charts.push(chart)
  return chart
}

function renderCharts() {
  const statusData = (stats.value.statusDistribution || []).map((item) => ({
    name: statusMeta[item.status]?.label || item.status,
    value: Number(item.total),
  }))
  initChart(statusChartEl.value)?.setOption({
    color: ['#94a3b8', '#0ea5e9', '#f59e0b', '#d97706', '#059669', '#64748b'],
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: '#64748b', fontSize: 11 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 3, borderRadius: 4 },
        label: { show: false },
        data: statusData.length ? statusData : [{ name: '暂无数据', value: 1, itemStyle: { color: '#e2e8f0' } }],
      },
    ],
  })

  const daySet = new Set()
  ;(stats.value.ticketTrend || []).forEach((item) => daySet.add(item.day))
  ;(stats.value.aiTrend || []).forEach((item) => daySet.add(item.day))
  const days = [...daySet].sort()
  const ticketMap = Object.fromEntries((stats.value.ticketTrend || []).map((i) => [i.day, i.total]))
  const aiMap = Object.fromEntries((stats.value.aiTrend || []).map((i) => [i.day, i.total]))
  initChart(trendChartEl.value)?.setOption({
    color: ['#0d9488', '#0ea5e9'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 10, icon: 'circle', textStyle: { color: '#64748b', fontSize: 11 } },
    grid: { left: 36, right: 22, top: 40, bottom: 26 },
    xAxis: {
      type: 'category',
      data: days,
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    series: [
      {
        name: '新增工单',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: days.map((d) => ticketMap[d] || 0),
        areaStyle: { color: 'rgba(13,148,136,0.12)' },
        lineStyle: { width: 2.5 },
      },
      {
        name: 'AI 调用',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: days.map((d) => aiMap[d] || 0),
        areaStyle: { color: 'rgba(14,165,233,0.10)' },
        lineStyle: { width: 2.5 },
      },
    ],
  })

  const categoryData = (stats.value.categoryDistribution || [])
    .map((item) => ({ name: categoryMeta[item.category] || item.category, value: Number(item.total) }))
    .sort((a, b) => a.value - b.value)
  initChart(categoryChartEl.value)?.setOption({
    color: palette,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 74, right: 26, top: 16, bottom: 18 },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    yAxis: {
      type: 'category',
      data: categoryData.map((i) => i.name),
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', fontSize: 11.5 },
    },
    series: [
      {
        type: 'bar',
        data: categoryData.map((i) => i.value),
        barWidth: 14,
        itemStyle: {
          borderRadius: [0, 7, 7, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: '#14b8a6' },
            { offset: 1, color: '#0ea5e9' },
          ]),
        },
      },
    ],
  })
}

function resizeAll() {
  charts.forEach((chart) => chart.resize())
}

onMounted(async () => {
  await load()
  window.addEventListener('resize', resizeAll)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeAll)
  charts.forEach((chart) => chart.dispose())
})
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}

.stat-card {
  padding: 15px 16px;
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.stat-icon {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 18px;
  flex: 0 0 38px;
}

.stat-icon.teal {
  background: #e6f7f5;
  color: #0f766e;
}

.stat-icon.amber {
  background: #fef3c7;
  color: #b45309;
}

.stat-icon.blue {
  background: #e0f2fe;
  color: #0369a1;
}

.stat-icon.indigo {
  background: #ede9fe;
  color: #6d28d9;
}

.stat-icon.rose {
  background: #ffe4e6;
  color: #be123c;
}

.stat-icon.green {
  background: #dcfce7;
  color: #15803d;
}

.stat-label {
  font-size: 12px;
  color: var(--muted);
}

.stat-value {
  font-size: 25px;
  font-weight: 750;
  letter-spacing: -0.03em;
  line-height: 1.2;
  margin-top: 3px;
}

.stat-value .unit {
  font-size: 12px;
  font-weight: 500;
  color: var(--muted);
  margin-left: 3px;
}

.stat-foot {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1.55fr 1fr;
  gap: 14px;
  margin-top: 14px;
}

.chart-card {
  overflow: hidden;
}

.chart {
  height: 255px;
  width: 100%;
  padding: 4px 8px 8px;
}

.bottom-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 14px;
  margin-top: 14px;
}

.recent-list {
  padding: 8px 12px 12px;
}

.recent-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}

.recent-item:hover {
  background: #f8fafc;
}

.recent-item + .recent-item {
  border-top: 1px dashed #eef2f7;
}

.recent-main {
  flex: 1;
  min-width: 0;
}

.recent-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #94a3b8;
  margin-top: 3px;
}

@media (max-width: 1360px) {
  .stat-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .chart-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
