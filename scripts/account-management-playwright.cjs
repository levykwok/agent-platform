#!/usr/bin/env node
/* End-to-end browser validation for the account lifecycle and administration console. */
const fs = require('node:fs')
const path = require('node:path')

function loadPlaywright() {
  try {
    return require('playwright')
  } catch (error) {
    if (!process.env.PLAYWRIGHT_MODULE) throw error
    return require(process.env.PLAYWRIGHT_MODULE)
  }
}

const { chromium } = loadPlaywright()
const baseUrl = (process.env.ACCOUNT_E2E_BASE_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const adminEmail = process.env.ACCOUNT_E2E_ADMIN_EMAIL || 'admin@platform.local'
const adminPassword = process.env.ACCOUNT_E2E_ADMIN_PASSWORD
const outputDir = path.resolve(process.env.ACCOUNT_E2E_OUTPUT || 'output/playwright/account-management')

if (!adminPassword) throw new Error('ACCOUNT_E2E_ADMIN_PASSWORD is required')
fs.mkdirSync(outputDir, { recursive: true })

const runId = Date.now().toString(36)
const applicantEmail = `account-e2e-${runId}@example.com`
const applicantPassword = 'AccountE2EPassword123!'
const projectName = `E2E Quality Lab ${runId}`
const managedOrgName = `E2E Managed Org ${runId}`
const results = []

function record(name, details = {}) {
  results.push({ name, passed: true, ...details })
}

async function login(page, email, password) {
  await page.goto(`${baseUrl}/platform/live/access`)
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL(url => url.pathname === '/platform/live', { timeout: 30_000 })
}

async function openAccounts(page) {
  await page.getByRole('button', { name: '账号审核' }).click()
  await page.getByRole('heading', { name: '账号管理' }).waitFor({ timeout: 30_000 })
}

async function main() {
  const browser = await chromium.launch({
    headless: process.env.ACCOUNT_E2E_HEADED !== 'true',
    ...(process.env.ACCOUNT_E2E_BROWSER
      ? { executablePath: process.env.ACCOUNT_E2E_BROWSER }
      : {}),
  })
  const publicContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await publicContext.newPage()
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(String(error)))

  try {
    await page.goto(`${baseUrl}/platform/live/access`)
    await page.getByRole('button', { name: '没有账号？申请访问' }).click()
    await page.getByRole('textbox', { name: '邮箱' }).fill(applicantEmail)
    await page.getByRole('textbox', { name: '姓名/昵称' }).fill('Account E2E User')
    await page.getByRole('textbox', { name: '项目或组织名称' }).fill(projectName)
    await page.getByRole('textbox', { name: '申请用途' }).fill('验证完整账号管理链路')
    await page.getByRole('button', { name: '提交申请' }).click()
    await page.getByText('申请已提交，请等待管理员审核。').waitFor()
    record('public application submitted', { applicantEmail })

    await page.getByRole('button', { name: '返回登录' }).click()
    await page.getByRole('textbox', { name: '邮箱' }).fill(adminEmail)
    await page.getByRole('textbox', { name: '密码' }).fill(adminPassword)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(url => url.pathname === '/platform/live')
    await openAccounts(page)
    const applicationRow = page.locator('tr').filter({ hasText: applicantEmail })
    await applicationRow.getByRole('button', { name: '通过' }).click()
    await page.getByRole('heading', { name: '通过账号申请' }).waitFor()
    await page.getByLabel('角色').selectOption('BUILDER')
    await page.getByRole('button', { name: '确认提交' }).click()
    const approvalMessage = page.locator('.alert.success')
    await approvalMessage.waitFor()
    const approvalText = await approvalMessage.textContent()
    const setupUrl = approvalText && approvalText.match(/https?:\/\/\S+/)?.[0]
    if (!setupUrl) throw new Error(`Approval did not expose the development setup URL: ${approvalText}`)
    await page.screenshot({ path: path.join(outputDir, '01-application-approved.png'), fullPage: true })
    record('administrator approved application')

    await page.goto(setupUrl)
    await page.getByRole('textbox', { name: '设置密码' }).fill(applicantPassword)
    await page.getByRole('textbox', { name: '确认密码' }).fill(applicantPassword)
    await page.getByRole('textbox', { name: '显示名称' }).fill('Account E2E User')
    await page.getByRole('button', { name: '完成设置' }).click()
    await page.waitForURL(url => url.pathname === '/platform/live')
    await page.getByText('BUILDER', { exact: true }).waitFor()
    record('one-time password setup completed')

    await page.goto(`${baseUrl}/platform/live/accounts`)
    await page.waitForURL(url => url.pathname === '/platform/live')
    if (await page.getByRole('button', { name: '账号审核' }).count()) {
      throw new Error('Non-admin user can see the account administration navigation item')
    }
    record('non-admin account route rejected')

    const adminContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    const adminPage = await adminContext.newPage()
    adminPage.on('pageerror', error => pageErrors.push(String(error)))
    await login(adminPage, adminEmail, adminPassword)
    await openAccounts(adminPage)

    await adminPage.getByRole('button', { name: '组织管理' }).click()
    await adminPage.getByRole('button', { name: '新建组织' }).click()
    await adminPage.getByRole('heading', { name: '新建组织' }).waitFor()
    await adminPage.getByLabel('组织名称').fill(managedOrgName)
    await adminPage.getByRole('button', { name: '保存组织' }).click()
    await adminPage.getByText(managedOrgName, { exact: true }).waitFor()
    record('organization created from administration console')

    await adminPage.getByRole('button', { name: '用户与权限' }).click()
    await adminPage.getByPlaceholder('搜索姓名或邮箱').fill(applicantEmail)
    const userRow = adminPage.locator('tr').filter({ hasText: applicantEmail })
    await userRow.waitFor()
    await userRow.getByRole('button', { name: /个$/ }).click()
    await adminPage.getByRole('heading', { name: '登录会话' }).waitFor()
    await adminPage.screenshot({ path: path.join(outputDir, '02-session-management.png'), fullPage: true })
    adminPage.once('dialog', dialog => dialog.accept())
    await adminPage.getByRole('button', { name: '撤销', exact: true }).first().click()
    await adminPage.getByText('REVOKED', { exact: true }).waitFor()
    record('individual user session revoked')

    await page.goto(`${baseUrl}/platform/live`)
    await page.waitForURL(url => url.pathname === '/platform/live/access')
    record('revoked browser session can no longer access platform')

    await adminPage.getByRole('button', { name: '关闭' }).last().click()
    const refreshedUserRow = adminPage.locator('tr').filter({ hasText: applicantEmail })
    await refreshedUserRow.getByRole('button', { name: '编辑' }).click()
    await adminPage.getByLabel('角色').selectOption('TESTER')
    await adminPage.getByRole('button', { name: '保存修改' }).click()
    await adminPage.getByText('账号、组织和角色已更新').waitFor()
    record('user role updated')

    await adminPage.getByRole('button', { name: '邮件队列' }).click()
    await adminPage.locator('tr').filter({ hasText: applicantEmail }).waitFor()
    await adminPage.screenshot({ path: path.join(outputDir, '03-email-outbox.png'), fullPage: true })
    record('email outbox visible to administrator')

    if (pageErrors.length) throw new Error(`Browser page errors: ${pageErrors.join(' | ')}`)
    await adminContext.close()
  } finally {
    await publicContext.close()
    await browser.close()
  }
}

main()
  .then(() => {
    const report = { ok: true, baseUrl, applicantEmail, results }
    fs.writeFileSync(path.join(outputDir, 'report.json'), JSON.stringify(report, null, 2))
    console.log(JSON.stringify(report, null, 2))
  })
  .catch(error => {
    const report = { ok: false, baseUrl, applicantEmail, results, error: String(error.stack || error) }
    fs.writeFileSync(path.join(outputDir, 'report.json'), JSON.stringify(report, null, 2))
    console.error(JSON.stringify(report, null, 2))
    process.exitCode = 1
  })
