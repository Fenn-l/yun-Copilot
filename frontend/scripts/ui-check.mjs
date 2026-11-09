import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = path.resolve(__dirname, '../../docs/screenshots')
fs.mkdirSync(SHOT_DIR, { recursive: true })

let passed = 0
let failed = 0

function check(name, condition, extra) {
  if (condition) {
    passed += 1
    console.log(`  [PASS] ${name}`)
  } else {
    failed += 1
    console.log(`  [FAIL] ${name}${extra ? ' -> ' + extra : ''}`)
  }
}

async function shot(page, name) {
  const file = path.join(SHOT_DIR, `${name}.png`)
  await page.screenshot({ path: file })
  console.log(`  [SHOT] ${path.relative(path.resolve(__dirname, '../..'), file)}`)
}

async function clickText(page, selector, text) {
  const found = await page.evaluate(
    (sel, txt) => {
      const el = [...document.querySelectorAll(sel)].find((e) => e.textContent.includes(txt))
      if (el) {
        el.click()
        return true
      }
      return false
    },
    selector,
    text,
  )
  if (!found) throw new Error(`未找到元素: ${selector} 含文本 "${text}"`)
}

async function quickLogin(page, label) {
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle2' })
  await clickText(page, '.demo-item', label)
}

async function gotoMenu(page, label) {
  await clickText(page, '.nav-item', label)
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--window-size=1440,900'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 })

  console.log('\n=== UI 冒烟测试 ===\n')

  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await page.waitForSelector('.login-page', { timeout: 20000 })
  check('登录页渲染', true)
  await shot(page, '01-login')

  console.log('\n1. 员工对话（RAG）')
  await quickLogin(page, '员工')
  await page.waitForSelector('.conv-panel', { timeout: 20000 })
  check('员工登录进入工作台', true)
  await shot(page, '02-chat-welcome')

  await page.click('.composer textarea')
  await page.type('.composer textarea', '公司邮箱登录失败，提示账号认证异常，应该怎么处理？', { delay: 8 })
  await clickText(page, '.composer-bar button', '发送')
  await page.waitForSelector('.citation', { timeout: 120000 })
  const chatText = await page.evaluate(() => document.body.innerText)
  check('AI 回答包含知识来源引用', chatText.includes('知识来源'))
  check('AI 回答内容非空', chatText.includes('认证') || chatText.includes('登录失败'))
  await page.evaluate(() => {
    const el = document.querySelector('.chat-body')
    if (el) el.scrollTop = el.scrollHeight
  })
  await shot(page, '03-chat-answer')
  console.log('  已生成带引用的 AI 回答')

  await gotoMenu(page, '工单中心')
  await page.waitForSelector('.el-table__row', { timeout: 20000 })
  check('员工看到自己的工单', true)
  await shot(page, '04-tickets-user')

  console.log('\n2. 客服工单与 Copilot')
  await quickLogin(page, '客服')
  await page.waitForSelector('.conv-panel', { timeout: 20000 })
  await gotoMenu(page, '工单中心')
  await page.waitForSelector('.el-table__row', { timeout: 20000 })
  await page.waitForFunction(() => !document.querySelector('.el-loading-mask'), { timeout: 20000 })
  await clickText(page, '.el-table__row button', '详情')
  await page.waitForSelector('.drawer-body', { timeout: 20000 })
  check('客服可打开工单详情', true)
  await shot(page, '05-ticket-detail')

  await clickText(page, '.copilot-head button', '生成摘要')
  await page.waitForFunction(() => document.querySelector('.copilot-result')?.innerText.includes('问题'), {
    timeout: 90000,
  })
  check('Copilot 生成工单摘要', true)
  await page.evaluate(() => {
    const card = document.querySelector('.copilot-card')
    if (card) card.scrollIntoView()
  })
  await shot(page, '06-ticket-copilot-summary')

  await clickText(page, '.copilot-head button', '智能分类')
  await page.waitForFunction(() => document.querySelector('.copilot-result')?.innerText.includes('推荐分类'), {
    timeout: 90000,
  })
  check('Copilot 给出分类建议', true)
  await shot(page, '07-ticket-copilot-classify')

  await gotoMenu(page, '企业知识库')
  await page.waitForSelector('.kb-card', { timeout: 20000 })
  check('知识库卡片渲染', true)
  await page.waitForFunction(() => document.body.innerText.includes('已完成'), { timeout: 20000 })
  check('示例文档已向量化完成', true)
  await shot(page, '08-knowledge')

  await gotoMenu(page, '运营看板')
  await page.waitForSelector('.stat-card', { timeout: 20000 })
  await page.waitForFunction(() => document.querySelectorAll('.chart').length >= 3, { timeout: 20000 })
  check('运营看板统计与图表渲染', true)
  await shot(page, '09-dashboard')

  console.log('\n3. 管理员系统设置')
  await quickLogin(page, '管理员')
  await page.waitForSelector('.sidebar', { timeout: 20000 })
  await gotoMenu(page, '系统设置')
  await page.waitForSelector('.provider-card', { timeout: 20000 })
  await page.waitForFunction(
    () => document.body.innerText.includes('百炼') && document.body.innerText.includes('Ollama'),
    { timeout: 20000 },
  )
  check('AI 模型设置页渲染，支持百炼/Ollama 切换', true)
  await shot(page, '10-settings-ai')

  await clickText(page, '.el-tabs__item', '成员管理')
  await page.waitForSelector('.mini-avatar', { timeout: 20000 })
  check('成员管理列表渲染', true)
  await shot(page, '11-settings-users')

  console.log(`\n=== UI 结果：通过 ${passed} 项，失败 ${failed} 项 ===\n`)
} catch (error) {
  failed += 1
  console.error('\nUI 冒烟异常:', error.message)
} finally {
  await browser.close()
}

if (failed > 0) process.exit(1)
