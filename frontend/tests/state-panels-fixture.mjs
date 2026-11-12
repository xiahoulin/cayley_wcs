// Isolated UI fixture: serves built assets and in-memory API responses on loopback only.
// npm run build && node tests/state-panels-fixture.mjs
// No requests are forwarded to WCS or physical devices. POST /__fixture/scenario changes test conditions.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const dist = path.resolve(fileURLToPath(new URL('../dist/', import.meta.url))), writes = []
let revision = 11, blocked = false, failState = false, rejectWrite = false
const base = { appCode: 'UI_FIXTURE', protocolType: 'sim', revisionId: revision, configurationEpoch: 1, enabled: true, workMode: 'maintenance', connected: true, desiredState: 'open', state: 'RUNNING', retryCount: 0 }
const apps = [{ ...base, appId: 1, appName: '编辑控件验证', deviceType: 'stacker' }, { ...base, appId: 2, appName: '自动门联动验证', deviceType: 'auto_door' }]
const p = (field, label, type, constraints = {}, overrides = {}) => ({ field, label, type, constraints, unit: '', scope: 'business', address: field, description: '', writable: true, readable: false, canSet: !blocked, reason: blocked ? '设备仍有活动任务或任务号占用' : '', quality: 'NO_READBACK', receivedAt: null, value: null, sampleValue: null, ...overrides })
function points(id) {
  return id === 2 ? [p('cmd_Open', '开门指令', 'UINT16', { enum: [0, 1] }), p('cmd_Close', '关门指令', 'UINT16', { enum: [0, 1] })] : [
    p('ready', '设备就绪', 'BOOL', {}, { readable: true, writable: false, canSet: false, quality: 'GOOD', value: true, receivedAt: Date.now() }),
    p('target', '目标速度', 'FLOAT64', { min: 0, max: 5 }, { unit: 'm/s' }),
    p('enabled', '功能启用', 'BOOL'), p('mode', '工作模式', 'UINT16', { enum: [1, 2, 3] }),
    p('label', '批次名称', 'STRING', { max_length: 20 }), p('coordinates', '目标坐标', 'ARRAY<INT16>', { length: 3 }),
    p('counter', '累计数量', 'UINT64', { wire_encoding: 'decimal_string' }),
    p('locked', '受限参数', 'UINT16', {}, { canSet: false, reason: '请先在应用管理切换到维护模式' }),
    p('internal', '内部复位', 'BOOL', {}, { scope: 'internal', writable: false, canSet: false }),
  ]
}
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost'), chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : {}
    const json = data => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)) }
    if (url.pathname === '/__fixture/calls') return json(writes)
    if (url.pathname === '/__fixture/scenario' && req.method === 'POST') {
      if ('blocked' in body) blocked = body.blocked
      if ('revision' in body) revision = body.revision
      if ('failState' in body) failState = body.failState
      if ('rejectWrite' in body) rejectWrite = body.rejectWrite
      return json({ revision, blocked, failState, rejectWrite })
    }
    if (url.pathname.startsWith('/api/')) {
      let data
      switch (url.pathname.slice(4)) {
        case '/login': data = { access_token: 'isolated-ui-fixture', user_name: '界面验证' }; break
        case '/connection/applications': data = apps.map(a => ({ ...a, revisionId: revision, lastHeartbeatAt: Date.now() })); break
        case '/connection/status': data = { slots: { used: 2, max: 6 }, connections: [] }; break
        case '/connection/state':
          if (failState) return json({ isSuccess: false, code: 503, errorMessage: '测试状态更新中断' })
          data = { ...apps.find(a => a.appId === body.id), revisionId: revision, points: points(body.id), serverTime: Date.now(), staleAfterMs: 6000 }; break
        case '/connection/state/set':
          if (rejectWrite || body.revisionId !== revision || blocked) return json({ isSuccess: false, code: 409, errorMessage: '设备状态已变化，设置未执行' })
          writes.push(body); data = { accepted: true }; break
        case '/application/all': case '/alarm/active': data = []; break
        case '/chain/list': data = { rows: [], totals: 0 }; break
        default: return json({ isSuccess: false, code: 404, errorMessage: '界面夹具未提供此接口' })
      }
      return json({ isSuccess: true, code: 0, data })
    }
    let file = path.resolve(dist, '.' + decodeURIComponent(url.pathname))
    if (file !== dist && !file.startsWith(dist + path.sep)) { res.statusCode = 403; return res.end() }
    if (!path.extname(file)) file = path.join(dist, 'index.html')
    const content = await readFile(file)
    res.setHeader('Content-Type', { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }[path.extname(file)] || 'application/octet-stream')
    res.end(content)
  } catch { res.statusCode = 404; res.end('Not found') }
})
server.listen(Number(process.env.CAYLEY_STATE_UI_PORT || 5197), '127.0.0.1', () => console.log(`State panel UI fixture: http://127.0.0.1:${server.address().port}`))
