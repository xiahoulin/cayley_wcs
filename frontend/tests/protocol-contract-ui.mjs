import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

// Run against a local Vite server. All management API requests are intercepted; no database or device I/O.
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.CAYLEY_BROWSER_MODULE_DIR ? path.join(process.env.CAYLEY_BROWSER_MODULE_DIR, 'playwright') : 'playwright')
const url = process.env.CAYLEY_UI_URL || 'http://127.0.0.1:5195'
const out = process.env.CAYLEY_UI_OUTPUT || path.resolve('test-results/protocol-contract-ui')
await mkdir(out, { recursive: true })
const protocols = [
  { id: 1, protocol_code: 'DEMO_OPCUA', protocol_name: '堆垛机 OPC UA', target_system: '设备', protocol_type: 'opcua', version: '1', lifecycle_state: 'published', published_revision_id: 11, row_version: 1 },
  { id: 2, protocol_code: 'DEMO_HTTP', protocol_name: 'HTTP 点位草稿', target_system: '应用', protocol_type: 'http', version: '2', lifecycle_state: 'draft', row_version: 1 },
]
const httpTypes = ['BOOL', 'STRING', 'INT8', 'UINT8', 'INT16', 'UINT16', 'INT32', 'UINT32', 'INT64', 'UINT64', 'FLOAT32', 'FLOAT64', 'DECIMAL']
const point = (id, field, direction, scope, type) => ({ id, protocol_id: 2, field_name: field, symbol_name: field === 'cmd_TaskType' ? '任务类型' : '状态点', address: field, data_type: type, logical_type: type === 'UINT' ? 'UINT16' : 'BOOL', value_range: '', template_code: 'stacker', template_version: 1, operation: direction === 'request' ? 'dispatch' : 'result', direction, point_scope: scope, required: true, nullable: false, constraints: {}, row_version: 1 })
const points = [point(21, 'cmd_TaskType', 'request', 'business', 'UINT'), point(22, 'status_Ready', 'response', 'business', 'BOOL'), point(23, 'cmd_ResetFault', 'request', 'internal', 'BOOL')]
const devicePoints = points.map(p => ({ ...p, protocol_id: 1 }))
const revisions = [{ id: 11, protocol_id: 1, revision: 1, fingerprint: 'test-fingerprint', points: devicePoints }]
const apps = [
  { id: 1, app_code: 'HTTP_CALLER', app_name: 'HTTP 调用应用', protocol_id: 1, protocol_revision_id: 11, direction: 'upstream', device_type: 'stacker', enabled: true, conn_params: {} },
  { id: 2, app_code: 'STACKER_SIM', app_name: '堆垛机仿真', protocol_id: 1, protocol_revision_id: 11, direction: 'downstream', device_type: 'stacker', enabled: true, conn_params: {} },
  { id: 3, app_code: 'BAD_CONTRACT', app_name: '不匹配设备', protocol_id: 1, protocol_revision_id: 11, direction: 'downstream', device_type: 'stacker', enabled: true, conn_params: {} },
]
const task = { id: 1, app_id: 2, task_no: 'WA1', task_type: '1', status: 'completed', handshake_step: 'complete', error_code: 0, priority: 1, payload: { cmd_TaskType: 1 }, result: { capturedAt: 123, quality: 'good', payload: { status_Ready: true } }, native_task_num: 23, protocol_revision_id: 11 }
const calls = []
const pageErrors = []
const browser = await chromium.launch({ headless: true, ...(process.env.CAYLEY_BROWSER_CHANNEL ? { channel: process.env.CAYLEY_BROWSER_CHANNEL } : {}), ...(process.env.CAYLEY_BROWSER_EXECUTABLE ? { executablePath: process.env.CAYLEY_BROWSER_EXECUTABLE } : {}) })
const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } })
page.on('pageerror', e => pageErrors.push(e.message))
page.on('dialog', dialog => dialog.accept())
await page.addInitScript(() => { localStorage.setItem('cayleywcs_token', 'ui-test-only'); localStorage.setItem('cayleywcs_user', 'UI 测试') })
await page.route('**/api/**', async route => {
  const request = route.request()
  if (!new URL(request.url()).pathname.startsWith('/api/')) { await route.continue(); return }
  const endpoint = new URL(request.url()).pathname.replace('/api', '')
  const body = request.postDataJSON() || {}
  calls.push({ endpoint, body })
  assert.equal(request.method(), 'POST')
  let data
  if (endpoint === '/dict/item/list-by-type') data = body.typeCode === 'protocol_type' ? [{ item_value: 'opcua', item_name: 'OPC UA' }, { item_value: 'http', item_name: 'HTTP' }] : [{ item_value: 'stacker', item_name: '堆垛机' }]
  else if (endpoint === '/protocol/list') data = { rows: protocols, totals: protocols.length }
  else if (endpoint === '/protocol/all') data = protocols
  else if (endpoint === '/protocol/detail') data = protocols.find(p => p.id === body.id)
  else if (endpoint === '/protocol/point/list') data = points.filter(p => p.protocol_id === body.id)
  else if (endpoint === '/protocol/revisions') data = revisions.filter(r => r.protocol_id === body.id)
  else if (endpoint === '/protocol/type/options') data = body.protocol_type === 'opcua' ? httpTypes.filter(type => !['UINT64', 'DECIMAL'].includes(type)) : httpTypes
  else if (endpoint === '/protocol/point/create') {
    data = { ...body, id: 100 + points.length, row_version: 1 }; points.push(data)
    protocols.find(p => p.id === body.protocol_id).row_version++
  } else if (endpoint === '/protocol/point/update') {
    const index = points.findIndex(p => p.id === body.id); assert.equal(body.row_version, points[index].row_version)
    data = { ...body, row_version: body.row_version + 1 }; points[index] = data
    protocols.find(p => p.id === body.protocol_id).row_version++
  } else if (endpoint === '/protocol/point/delete') {
    const index = points.findIndex(p => p.id === body.id); assert.equal(body.row_version, points[index].row_version)
    protocols.find(p => p.id === points[index].protocol_id).row_version++; points.splice(index, 1); data = true
  } else if (endpoint === '/protocol/publish') {
    const p = protocols.find(p => p.id === body.id); assert.equal(body.row_version, p.row_version)
    p.lifecycle_state = 'published'; p.published_revision_id = 12; data = p
    revisions.push({ id: 12, protocol_id: p.id, revision: 1, fingerprint: 'published-test', points: points.filter(p => p.protocol_id === body.id) })
  } else if (endpoint === '/protocol/clone') {
    const source = protocols.find(p => p.id === body.id)
    data = { ...source, id: 3, protocol_code: 'COPY_DRAFT', protocol_name: '复制草稿', lifecycle_state: 'draft', published_revision_id: null, row_version: 1 }
    protocols.push(data); points.push(...devicePoints.map((p, i) => ({ ...p, id: 300 + i, protocol_id: 3 })))
  } else if (endpoint === '/application/list') data = { rows: apps, totals: apps.length }
  else if (endpoint === '/application/all') data = apps
  else if (endpoint === '/application/adapter/options') data = [{ code: 'standard', name: '通用协议适配器', protocolType: '', deviceTypes: [] }]
  else if (endpoint === '/application/update') data = body
  else if (endpoint === '/binding/all') data = []
  else if (endpoint === '/binding/granted') data = [2]
  else if (endpoint === '/binding/check') data = { compatible: body.upstream_app_id === 1 && body.downstream_app_id === 2, upstreamSnapshotId: 11, downstreamSnapshotId: 11, differences: body.downstream_app_id === 2 ? [] : [{ field: 'status_Ready', direction: 'response', reason: '逻辑类型不一致', expected: 'BOOL', actual: 'STRING' }] }
  else if (endpoint === '/binding/create' || endpoint === '/binding/grant') data = body
  else if (endpoint === '/connection/status') data = { slots: { used: 1, max: 6 }, connections: [{ appId: 2, appCode: 'STACKER_SIM', appName: '堆垛机仿真', protocolType: 'opcua', deviceType: 'stacker', state: 'RUNNING', retryCount: 0, lastHeartbeatAt: Date.now(), payload: {}, quality: 'good', capturedAt: Date.now() }] }
  else if (endpoint === '/connection/applications') data = [{ appId: 2, appCode: 'STACKER_SIM', appName: '堆垛机仿真', protocolType: 'opcua', deviceType: 'stacker', revisionId: 11, configurationEpoch: 1, enabled: true, workMode: 'maintenance', connected: true, state: 'RUNNING', desiredState: 'open', lastHeartbeatAt: Date.now() }]
  else if (endpoint === '/connection/state') data = { appId: 2, appName: '堆垛机仿真', revisionId: 11, configurationEpoch: 1, serverTime: Date.now(), points: devicePoints.map(p => ({ field: p.field_name, label: p.symbol_name, type: p.logical_type, scope: p.point_scope, constraints: {}, unit: '', readable: p.direction === 'response', writable: p.direction === 'request' && p.point_scope === 'business', canSet: p.direction === 'request' && p.point_scope === 'business', reason: p.direction === 'response' ? '设备反馈，只读' : '', value: p.direction === 'response' ? true : null, quality: p.direction === 'response' ? 'GOOD' : 'NO_READBACK', receivedAt: Date.now() })) }
  else if (endpoint === '/connection/state/set') data = { payload: body.payload, quality: 'good', capturedAt: 123 }
  else if (endpoint === '/task/list') data = { rows: [task], totals: 1 }
  else if (endpoint === '/task/detail') data = task
  else throw new Error(`Unmocked API ${endpoint}`)
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ isSuccess: true, code: 0, data }) })
})
const field = (scope, label) => scope.locator('label').filter({ hasText: new RegExp(`^${label}$`) }).locator('..').locator('input,select,textarea').first()
async function screenshot(name) { await page.screenshot({ path: path.join(out, name), fullPage: true }) }
try {
  await page.goto(`${url}/#/protocol`)
  await page.getByRole('row').filter({ hasText: 'DEMO_HTTP' }).getByRole('button', { name: '点位与版本' }).click()
  let panel = page.locator('.point-modal')
  await panel.getByText('3 个点位', { exact: false }).waitFor()
  await panel.getByRole('button', { name: '新增点位' }).click()
  let editor = page.locator('.point-editor')
  assert.equal(await editor.locator('label').filter({ hasText: /^分组$/ }).count(), 0)
  await field(editor, '逻辑字段编码（区分大小写）').fill('cmd_TestValue')
  await field(editor, '方向').selectOption('response')
  assert.equal(await field(editor, '操作').inputValue(), 'result')
  await field(editor, '方向').selectOption('request')
  assert.equal(await field(editor, '操作').inputValue(), 'dispatch')
  await field(editor, '中文名称').fill('测试参数')
  await field(editor, '原生地址').fill('WCS_Task.From_WCS.cmd_TestValue')
  // The initial BOOL value must not filter the complete menu as browser datalist did.
  const logicalSelect = field(editor, '逻辑类型')
  const nativeSelect = field(editor, '协议原生类型')
  assert.equal(await logicalSelect.locator('optgroup[label="基础类型"] option').count(), 13)
  assert.equal(await logicalSelect.locator('optgroup[label="数组类型"] option').count(), 13)
  assert.equal(await nativeSelect.locator('optgroup[label="基础类型"] option').count(), httpTypes.length)
  assert.equal(await nativeSelect.locator('optgroup[label="数组类型"] option').count(), httpTypes.length)
  assert.equal(await editor.locator('#logical-types, #native-types').count(), 0)
  for (const type of ['INT16', 'FLOAT64', 'STRING', 'ARRAY<INT32>', 'UINT16']) {
    await logicalSelect.selectOption(type)
    assert.equal(await logicalSelect.inputValue(), type)
  }
  await nativeSelect.selectOption('UINT16')
  await nativeSelect.selectOption('__manual_type_declaration__')
  await editor.getByLabel('协议原生类型手动声明', { exact: true }).fill('UINT')
  await field(editor, '缩放系数（当前支持1）').fill('1')
  await field(editor, '最小值').fill('0'); await field(editor, '最小值').press('Tab')
  await field(editor, '最大值').fill('100'); await field(editor, '最大值').press('Tab')
  await screenshot('point-editor.png')
  await editor.getByRole('button', { name: '保存点位' }).click()
  await editor.waitFor({ state: 'hidden' })
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body.constraints.max, '100')
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body.scale, '1')
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body.logical_type, 'UINT16')
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body.data_type, 'UINT')
  assert.equal(Object.hasOwn(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body, 'point_group'), false)
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/create').at(-1).body.address, 'WCS_Task.From_WCS.cmd_TestValue')
  const created = panel.getByRole('row').filter({ hasText: 'cmd_TestValue' })
  await created.getByRole('button', { name: '编辑', exact: true }).click()
  editor = page.locator('.point-editor')
  assert.equal(await field(editor, '协议原生类型').inputValue(), 'UINT')
  assert.equal(await editor.locator('label').filter({ hasText: /^分组$/ }).count(), 0)
  assert.equal(await field(editor, '原生地址').inputValue(), 'WCS_Task.From_WCS.cmd_TestValue')
  assert.equal(await field(editor, '协议原生类型').locator('optgroup[label="当前类型（保留原值）"] option').count(), 1)
  await field(editor, '完整枚举（JSON 数组）').fill('bad-json'); await field(editor, '完整枚举（JSON 数组）').press('Tab')
  assert.equal(await editor.getByRole('button', { name: '保存点位' }).isDisabled(), true)
  await field(editor, '完整枚举（JSON 数组）').fill('[1,2]'); await field(editor, '完整枚举（JSON 数组）').press('Tab')
  await editor.getByRole('button', { name: '保存点位' }).click(); await editor.waitFor({ state: 'hidden' })
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/update').length, 1)
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/update').at(-1).body.data_type, 'UINT')
  assert.equal(Object.hasOwn(calls.filter(c => c.endpoint === '/protocol/point/update').at(-1).body, 'point_group'), false)
  await created.getByRole('button', { name: '删除', exact: true }).click(); await created.waitFor({ state: 'hidden' })
  // Arrays are selectable for every supported base; fixed declarations and aliases remain explicit.
  await panel.getByRole('button', { name: '新增点位' }).click()
  editor = page.locator('.point-editor')
  await field(editor, '逻辑字段编码（区分大小写）').fill('status_TestArray')
  await field(editor, '方向').selectOption('response')
  await field(editor, '逻辑类型').selectOption('ARRAY<INT16>')
  await field(editor, '协议原生类型').selectOption('ARRAY<INT16>')
  await field(editor, '协议原生类型').selectOption('__manual_type_declaration__')
  await editor.getByLabel('协议原生类型手动声明', { exact: true }).fill('ARRAY[60] OF INT')
  await field(editor, '数组固定长度').fill('60'); await field(editor, '数组固定长度').press('Tab')
  await editor.getByRole('button', { name: '保存点位' }).click(); await editor.waitFor({ state: 'hidden' })
  const arrayRow = panel.getByRole('row').filter({ hasText: 'status_TestArray' })
  await arrayRow.getByRole('button', { name: '编辑', exact: true }).click()
  editor = page.locator('.point-editor')
  assert.equal(await field(editor, '逻辑类型').inputValue(), 'ARRAY<INT16>')
  assert.equal(await field(editor, '协议原生类型').inputValue(), 'ARRAY[60] OF INT')
  await editor.getByRole('button', { name: '保存点位' }).click(); await editor.waitFor({ state: 'hidden' })
  assert.equal(calls.filter(c => c.endpoint === '/protocol/point/update').at(-1).body.data_type, 'ARRAY[60] OF INT')
  await panel.getByRole('button', { name: '新增点位' }).click()
  editor = page.locator('.point-editor')
  await field(editor, '逻辑字段编码（区分大小写）').fill('status_TestText')
  await field(editor, '方向').selectOption('response')
  await field(editor, '逻辑类型').selectOption('STRING')
  await field(editor, '协议原生类型').selectOption('__manual_type_declaration__')
  await editor.getByLabel('协议原生类型手动声明', { exact: true }).fill('TEXT')
  await editor.getByRole('button', { name: '保存点位' }).click(); await editor.waitFor({ state: 'hidden' })
  await panel.getByRole('button', { name: '检查并发布' }).click()
  await panel.getByText('已发布', { exact: true }).waitFor()
  assert.equal(await panel.getByRole('button', { name: '新增点位' }).count(), 0)
  await panel.getByRole('row').filter({ hasText: 'status_TestText' }).getByRole('button', { name: '查看', exact: true }).click()
  editor = page.locator('.point-editor')
  assert.equal(await field(editor, '协议原生类型').inputValue(), 'TEXT')
  assert.equal(await editor.locator('label').filter({ hasText: /^分组$/ }).count(), 0)
  assert.equal(await field(editor, '协议原生类型').isDisabled(), true)
  assert.equal(await field(editor, '逻辑类型').inputValue(), 'STRING')
  await editor.getByRole('button', { name: '关闭', exact: true }).click()
  await screenshot('point-published.png')
  await panel.getByRole('button', { name: '复制草稿' }).click()
  await panel.getByRole('heading', { name: '复制草稿 点位与版本' }).waitFor()
  await panel.getByRole('button', { name: '关闭', exact: true }).click()

  await page.goto(`${url}/#/application`)
  await page.getByRole('row').filter({ hasText: 'STACKER_SIM' }).getByRole('button', { name: '编辑', exact: true }).click()
  let modal = page.locator('.modal')
  await field(modal, '固定发布版本').selectOption('11')
  await modal.getByText('堆垛机 使用 stacker 模板', { exact: true }).waitFor()
  await screenshot('application-version.png')
  await modal.getByRole('button', { name: '取消', exact: true }).click()

  await page.goto(`${url}/#/binding-detail`)
  await page.getByRole('button', { name: '手动新建单条', exact: false }).click()
  modal = page.locator('.modal')
  await modal.getByText('点位匹配通过', { exact: true }).waitFor()
  await field(modal, '下位侧应用').selectOption('3')
  await modal.getByText('逻辑类型不一致', { exact: true }).waitFor()
  assert.equal(await modal.getByRole('button', { name: '保存', exact: true }).isDisabled(), true)
  await screenshot('binding-difference.png')
  await modal.getByRole('button', { name: '取消', exact: true }).click()

  await page.goto(`${url}/#/binding-grant`)
  const badCard = page.locator('.dev').filter({ hasText: '不匹配设备' })
  await badCard.getByText('不可连接', { exact: true }).waitFor()
  assert.equal(await badCard.locator('input[type="checkbox"]').isDisabled(), true)
  await screenshot('grant-checks.png')

  await page.goto(`${url}/#/connection`)
  await page.getByRole('button', { name: '状态设置', exact: true }).click()
  modal = page.locator('.state-modal')
  await modal.locator('.point-card[data-field="status_Ready"]').getByText('只读', { exact: true }).waitFor()
  assert.equal(await modal.locator('table').count(), 0)
  assert.equal(await modal.locator('.point-card[data-field="status_Ready"] button').count(), 0)
  assert.equal(await modal.getByText('cmd_ResetFault', { exact: true }).count(), 0)
  assert.equal(calls.filter(c => c.endpoint === '/connection/read').length, 0)
  await modal.getByRole('button', { name: '设置任务类型', exact: true }).click()
  editor = page.getByRole('dialog', { name: '设置任务类型', exact: true })
  await editor.getByLabel('设置值', { exact: true }).fill('9007199254740993')
  const count = calls.filter(c => c.endpoint === '/connection/state/set').length
  await editor.getByRole('button', { name: '确认设置' }).click()
  await editor.getByText('整数超过浏览器安全范围', { exact: false }).waitFor()
  assert.equal(calls.filter(c => c.endpoint === '/connection/state/set').length, count)
  await editor.getByLabel('设置值', { exact: true }).fill('1')
  await editor.getByRole('button', { name: '确认设置' }).click()
  await editor.waitFor({ state: 'hidden' })
  await modal.getByText('设置已提交，当前状态以设备后续反馈为准。', { exact: true }).waitFor()
  assert.deepEqual(calls.filter(c => c.endpoint === '/connection/state/set').at(-1).body, { appId: 2, revisionId: 11, configurationEpoch: 1, payload: { cmd_TaskType: 1 } })
  await screenshot('point-io.png')
  await modal.getByRole('button', { name: '关闭', exact: true }).click()

  await page.goto(`${url}/#/task`)
  await page.getByRole('button', { name: '点位与结果' }).click()
  await page.locator('.task-info').getByText('设备任务号 23', { exact: true }).waitFor()
  await screenshot('task-result.png')
  assert.deepEqual(pageErrors, [])
  console.log(JSON.stringify({ status: 'passed', checks: ['complete grouped type menus independent of BOOL', 'select alternate scalar and array types', 'native aliases and fixed arrays preserve on reopen/save/view', 'point create/edit/delete with version', 'point group absent from create/edit/view and submitted payloads', 'native address containing From_WCS preserved', 'structured constraints reject invalid JSON', 'publish locks point changes', 'clone creates editable draft', 'application revision/template', 'binding mismatch blocks save', 'grant mismatch disabled', 'read/write JSON payload only', 'internal points excluded', 'unsafe JSON integer rejected', 'task request/result shown'], calls: calls.length, screenshots: out }))
} finally { await browser.close() }
