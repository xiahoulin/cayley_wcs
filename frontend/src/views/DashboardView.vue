<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  connectionStatus, alarmActive, chainList,
  monitorWsTicket, type ConnectionSnapshot, type SlotUsage, type Alarm, type TaskChain,
} from '../api/wcs'
import { auth } from '../store/auth'
import { dashboardDevices, saveDashboardSelection, type DashboardDevice } from '../api/dashboard'
import DashboardDeviceCard from '../components/DashboardDeviceCard.vue'
import { pointDisplayName } from '../utils/pointLabels'

const conns = ref<ConnectionSnapshot[]>([])
const slots = ref<SlotUsage>({ max: 0, used: 0 })
const alarms = ref<Alarm[]>([])
const chains = ref<TaskChain[]>([])
const devices = ref<DashboardDevice[]>([]), deviceError = ref(''), devicesFetchedAt = ref(Date.now()), clockNow = ref(Date.now())
const editing = ref<DashboardDevice>(), selectedFields = ref<string[]>([]), saving = ref(false), editError = ref('')
let devicesPending = false, devicesVersion = 0
const wsConnected = ref(false)
let timer: number | undefined
let reconnectTimer: number | undefined
let ws: WebSocket | undefined
let stopped = false
let connecting = false
let epoch = 0
let frameVersion = 0
let lastFrameAt = 0
let wsOpenedAt = 0
let lastCalibrationAt = 0
let reconnectAttempts = 0
let connectionsPending = false
let alarmsPending = false
let chainsPending = false
const requests = new Set<AbortController>()
const FRAME_STALE_MS = 5000
const WS_RECONNECT_SILENCE_MS = 12_000
const FRAME_CLOCK_SKEW_MS = 30_000
const REST_CALIBRATION_MS = 30_000

const runningCount = computed(() => conns.value.filter((c) => c.state === 'RUNNING').length)
const runningChains = computed(() => chains.value.filter((c) => c.status === 'running' || c.status === 'pending'))

function levelClass(l: string) {
  return l === 'warn' ? 'warn' : l === 'info' ? 'muted' : 'err'
}
function chainStateClass(s: string) {
  if (s === 'completed') return 'ok'
  if (s === 'failed') return 'err'
  if (s === 'running') return 'warn'
  return 'muted'
}

function wsHealthy() {
  return ws?.readyState === WebSocket.OPEN && lastFrameAt > 0
    && performance.now() - lastFrameAt <= FRAME_STALE_MS
}

async function request<T>(call: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  requests.add(controller)
  try {
    return await call(controller.signal)
  } finally {
    requests.delete(controller)
  }
}

async function loadDevices(force = false) {
  if (devicesPending && !force) return
  const version = ++devicesVersion
  devicesPending = true
  try {
    const states = await request(dashboardDevices)
    if (stopped || version !== devicesVersion) return
    devices.value = [...states].sort((a,b) => a.appId - b.appId); devicesFetchedAt.value = Date.now(); deviceError.value = ''
  } catch (e) { if (!stopped && version === devicesVersion) deviceError.value = e instanceof Error ? e.message : '点位状态更新失败' }
  finally { if (version === devicesVersion) devicesPending = false }
}
function editDisplay(device: DashboardDevice) {
  editing.value = device; selectedFields.value = [...device.selectedFields]; editError.value = ''
}
function closeEditor() { if (!saving.value) editing.value = undefined }
async function saveDisplay() {
  if (!editing.value || saving.value) return
  saving.value = true; editError.value = ''
  try {
    await saveDashboardSelection(editing.value.appId, editing.value.revisionId, selectedFields.value)
    if (stopped) return
    editing.value = undefined
    await loadDevices(true)
  } catch (e) { editError.value = e instanceof Error ? e.message : '保存失败' }
  finally { saving.value = false }
}

async function loadConnections() {
  if (connectionsPending) return
  connectionsPending = true
  const requestEpoch = epoch
  const requestFrameVersion = frameVersion
  try {
    const st = await request(connectionStatus)
    if (stopped || requestEpoch !== epoch || requestFrameVersion !== frameVersion) return
    conns.value = st.connections
    slots.value = st.slots
  } catch { /* ignore transient */ }
  finally { connectionsPending = false }
}

async function loadAlarms() {
  if (alarmsPending) return
  alarmsPending = true
  const requestEpoch = epoch
  const requestFrameVersion = frameVersion
  try {
    const rows = await request(alarmActive)
    if (stopped || requestEpoch !== epoch || requestFrameVersion !== frameVersion) return
    alarms.value = rows
  } catch { /* ignore transient */ }
  finally { alarmsPending = false }
}

async function loadChains() {
  if (chainsPending) return
  chainsPending = true
  try {
    const rows = await request((signal) => chainList(1, 100, signal))
    if (!stopped) chains.value = rows.rows
  } catch { /* ignore transient */ }
  finally { chainsPending = false }
}

function refresh() {
  clockNow.value = Date.now()
  wsConnected.value = wsHealthy()
  const now = performance.now()
  if (ws?.readyState === WebSocket.OPEN && now - Math.max(lastFrameAt, wsOpenedAt) > WS_RECONNECT_SILENCE_MS) {
    ws.close()
  }
  if (!wsConnected.value || now - lastCalibrationAt >= REST_CALIBRATION_MS) {
    if (wsConnected.value) lastCalibrationAt = now
    void loadConnections()
    void loadAlarms()
  }
  void loadChains()
  void loadDevices()
}

interface MonitorFrame {
  type: 'monitor'
  seq: number
  ts: number
  connections: ConnectionSnapshot[]
  slots: SlotUsage
  alarms: Alarm[]
}

function validFrame(value: unknown): value is MonitorFrame {
  if (!value || typeof value !== 'object') return false
  const frame = value as Partial<MonitorFrame>
  const now = Date.now()
  return frame.type === 'monitor' && Number.isSafeInteger(frame.seq) && (frame.seq ?? -1) >= 0
    && typeof frame.ts === 'number' && Math.abs(now - frame.ts) <= FRAME_CLOCK_SKEW_MS
    && Array.isArray(frame.connections) && Array.isArray(frame.alarms)
    && !!frame.slots && typeof frame.slots.max === 'number' && typeof frame.slots.used === 'number'
}

function scheduleReconnect() {
  if (stopped || reconnectTimer !== undefined || !auth.token) return
  const delay = Math.min(30_000, 1000 * (2 ** Math.min(reconnectAttempts++, 5)))
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = undefined
    void connectWs()
  }, delay)
}

async function connectWs() {
  if (stopped || connecting || ws?.readyState === WebSocket.OPEN
    || ws?.readyState === WebSocket.CONNECTING || !auth.token) return
  connecting = true
  const session = ++epoch
  lastFrameAt = 0
  wsOpenedAt = 0
  wsConnected.value = false
  try {
    const ticket = await request(monitorWsTicket)
    if (stopped || session !== epoch) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${proto}://${location.host}/ws/monitor?ticket=${encodeURIComponent(ticket.ticket)}`)
    ws = socket
    let lastSeq = -1
    socket.onopen = () => {
      if (ws === socket && epoch === session) {
        wsOpenedAt = performance.now()
        reconnectAttempts = 0
      }
    }
    socket.onclose = () => {
      if (ws !== socket || epoch !== session || stopped) return
      ws = undefined
      wsConnected.value = false
      scheduleReconnect()
    }
    socket.onerror = () => {
      if (ws !== socket || epoch !== session || stopped) return
      wsConnected.value = false
      socket.close()
      scheduleReconnect()
    }
    socket.onmessage = (ev) => {
      if (ws !== socket || epoch !== session || stopped) return
      try {
        const msg: unknown = JSON.parse(ev.data)
        if (!validFrame(msg) || msg.seq <= lastSeq) return
        lastSeq = msg.seq
        frameVersion++
        lastFrameAt = performance.now()
        wsConnected.value = true
        conns.value = msg.connections
        slots.value = msg.slots
        alarms.value = msg.alarms
      } catch { /* ignore */ }
    }
  } catch { scheduleReconnect() }
  finally { connecting = false }
}

onMounted(() => {
  refresh()
  timer = window.setInterval(refresh, 2000)
  void connectWs()
})
onUnmounted(() => {
  stopped = true
  epoch++
  if (timer) clearInterval(timer)
  if (reconnectTimer !== undefined) clearTimeout(reconnectTimer)
  for (const controller of requests) controller.abort()
  ws?.close()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">实时看板</h2>
      <span :class="['tag', wsConnected ? 'ok' : 'muted']">{{ wsConnected ? 'WebSocket 已连' : '轮询模式' }}</span>
      <span class="spacer"></span>
    </div>

    <div class="cards">
      <div class="panel stat"><div class="n">{{ conns.length }}</div><div class="l">连接数</div></div>
      <div class="panel stat"><div class="n" style="color:var(--ok)">{{ runningCount }}</div><div class="l">运行中</div></div>
      <div class="panel stat"><div class="n" style="color:var(--warn)">{{ runningChains.length }}</div><div class="l">进行中链</div></div>
      <div class="panel stat"><div class="n" style="color:var(--err)">{{ alarms.length }}</div><div class="l">活动报警</div></div>
      <div class="panel stat"><div class="n">{{ slots.used }}/{{ slots.max }}</div><div class="l">连接槽</div></div>
    </div>

    <div class="layout">
      <div class="devices">
        <p v-if="deviceError" class="tag err device-error" role="alert">{{ deviceError }}</p>
        <DashboardDeviceCard v-for="device in devices" :key="device.appId" :device="device" :fetched-at="devicesFetchedAt" :now="clockNow" @edit="editDisplay" />
        <div v-if="!devices.length" class="panel" style="padding:24px;color:var(--muted)">{{ conns.length ? '正在加载应用点位…' : '暂无连接。请到「连接监控」建立连接。' }}</div>
      </div>

      <div class="side-col">
        <div class="panel alarms">
          <div class="alarm-head">活动报警</div>
          <div v-for="a in alarms" :key="a.id" class="alarm-row">
            <span :class="['tag', levelClass(a.level)]">{{ a.level }}</span>
            <div class="alarm-msg">
              <div>{{ a.message }}</div>
              <small class="muted">应用#{{ a.app_id }} · 码{{ a.fault_code }}</small>
            </div>
          </div>
          <div v-if="!alarms.length" style="padding:16px;color:var(--muted)">无活动报警</div>
        </div>

        <div class="panel alarms">
          <div class="alarm-head">进行中任务链</div>
          <div v-for="c in runningChains" :key="c.id" class="alarm-row">
            <span :class="['tag', chainStateClass(c.status)]">{{ c.status }}</span>
            <div class="alarm-msg">
              <div>{{ c.chain_no }}</div>
              <small class="muted">策略 {{ c.select_strategy }} · 优先级 {{ c.priority }}</small>
            </div>
          </div>
          <div v-if="!runningChains.length" style="padding:16px;color:var(--muted)">无进行中任务链</div>
        </div>
      </div>
    </div>
    <div v-if="editing" class="modal-mask" @click.self="closeEditor" @keydown.esc.stop="closeEditor">
      <form class="panel modal display-editor" role="dialog" aria-modal="true" aria-label="看板展示编辑" @submit.prevent="saveDisplay">
        <div class="toolbar"><h3>看板展示编辑</h3><span class="spacer"></span><button type="button" :disabled="saving" @click="closeEditor">关闭</button></div>
        <p class="editor-app">{{ editing.appName }}</p><p class="editor-hint">勾选需要展示的点位，每行显示两个；内容超出后可在应用看板内滚动。</p>
        <div class="toolbar"><span>已选择 {{ selectedFields.length }} 项</span><span class="spacer"></span><button type="button" :disabled="saving" @click="selectedFields = editing.points.map(p => p.field)">全选</button><button type="button" :disabled="saving" @click="selectedFields = []">清空</button></div>
        <div class="point-choices"><label v-for="p in editing.points" :key="p.field"><input v-model="selectedFields" type="checkbox" :value="p.field" :disabled="saving" :aria-label="pointDisplayName(p)" /><span>{{ pointDisplayName(p) }}<small v-if="p.unit">（{{ p.unit }}）</small><small v-if="!p.readable"> · 未提供回读</small></span></label></div>
        <p v-if="editError" class="tag err" role="alert">{{ editError }}</p>
        <div class="toolbar editor-footer"><small>选择按当前账号和应用保存</small><span class="spacer"></span><button type="button" :disabled="saving" @click="closeEditor">取消</button><button class="primary" :disabled="saving" type="submit">{{ saving ? '正在保存…' : '保存展示' }}</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 14px; }
.stat { padding: 16px; text-align: center; }
.stat .n { font-size: 28px; font-weight: 700; }
.stat .l { color: var(--muted); margin-top: 2px; }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 14px; }
.devices { min-width: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr)); gap: 12px; align-content: start; }
.device-error { grid-column: 1 / -1; margin: 0; }
.side-col { display: flex; flex-direction: column; gap: 14px; align-self: start; }
.alarms { padding: 0; }
.alarm-head { padding: 12px 14px; font-weight: 600; border-bottom: 1px solid var(--border); }
.alarm-row { display: flex; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--border); align-items: flex-start; }
.alarm-msg { min-width: 0; }
.muted { color: var(--muted); }
.display-editor { width: min(760px, 96vw); display: flex; flex-direction: column; overflow: hidden; }
.display-editor h3 { margin: 0; } .editor-app { margin: 0 0 6px; } .editor-hint,.editor-footer small { font-size: 12px; color: var(--muted); }
.point-choices { overflow: auto; min-height: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 2px; }
.point-choices label { display: flex; gap: 8px; align-items: flex-start; padding: 10px; border: 1px solid var(--border); border-radius: 5px; font-size: 13px; }
.point-choices input { width: auto; flex-shrink: 0; margin-top: 3px; } .point-choices span { overflow-wrap: anywhere; } .point-choices small { color: var(--muted); font-size: 11px; }
.editor-footer { margin: 14px 0 0; flex-shrink: 0; } .display-editor > p,.display-editor > .toolbar { flex-shrink: 0; }
@media (max-width: 1100px) { .layout { grid-template-columns: minmax(0, 1fr); } .side-col { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } .side-col,.point-choices { grid-template-columns: minmax(0, 1fr); } }
</style>
