<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
import { connectionStatus, connectionOpen, connectionClose, connectionReconnect, type SlotUsage } from '../api/wcs'
import { monitoredApplications, applicationState, setApplicationState, type MonitoredApplication, type ApplicationState, type StatePoint } from '../api/deviceState'
import { agvConnectionLabel, gatewayStateLabel } from '../utils/agvLabels'
import { linkedDoorPoint, pointQuality, stateSettingPayload } from '../utils/statePoints'
import AgvCollectionPanel from '../components/AgvCollectionPanel.vue'
import AgvNavigationPanel from '../components/AgvNavigationPanel.vue'
import PointStatePanels from '../components/PointStatePanels.vue'
import PointValueEditor from '../components/PointValueEditor.vue'

const apps = ref<MonitoredApplication[]>([])
const slots = ref<SlotUsage>({ max: 0, used: 0 })
const error = ref(''), busy = ref(false), filter = ref('')
const stateId = ref<number>(), state = ref<ApplicationState>(), stateError = ref(''), notice = ref('')
const internal = ref(false), search = ref('')
const editing = ref<{ state: ApplicationState; field: string }>(), editError = ref('')
let editTrigger: HTMLElement | null = null
const config = ref<{ agvAppId?: number; sensorKind?: string }>()
const navigation = ref<MonitoredApplication>()
const now = ref(Date.now()), fetchedAt = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval>
let timer: ReturnType<typeof setTimeout>, disposed = false, refreshing = false, stateRequest = 0
const visibleApps = computed(() => apps.value.filter(a => (a.appName + ' ' + a.appCode).toLowerCase().includes(filter.value.toLowerCase())))
const points = computed(() => (state.value?.points ?? []).filter(p => (internal.value || p.scope === 'business') && (p.label + ' ' + p.field).toLowerCase().includes(search.value.toLowerCase())))
const settingsLocked = computed(() => busy.value || !!stateError.value || now.value - fetchedAt.value >= 6000)
const editingPoint = computed(() => state.value?.points.find(p => p.field === editing.value?.field))
const editReason = computed(() => {
  if (!state.value || !editing.value || !editingPoint.value) return '点位已变化，请重新打开设置窗口。'
  const previous = editing.value.state, current = state.value, point = editingPoint.value
  if (previous.appId !== current.appId || previous.revisionId !== current.revisionId || previous.configurationEpoch !== current.configurationEpoch) return '应用点位版本已变化，请重新打开设置窗口。'
  if (stateError.value || now.value - fetchedAt.value >= 6000) return '状态更新中断，请刷新状态后重试。'
  if (!point.writable || !point.canSet) return point.reason || '该点位当前不可设置'
  const linked = linkedDoorPoint(current, point)
  if (current.deviceType === 'auto_door' && ['cmd_Open', 'cmd_Close'].includes(point.field) && (!linked?.writable || !linked.canSet)) return '自动门联动点位当前不可设置'
  return ''
})
const protocolNames: Record<string, string> = { sim: '仿真协议', opcua: 'OPC UA', modbus_tcp: 'Modbus TCP', s7: 'S7', http: 'HTTP', mqtt: 'MQTT', tcp: 'TCP' }
const adapterNames: Record<string, string> = { seer_agv: '仙工 AGV 适配器', dr164_sensor: 'DR164 共享采集适配器' }
function stateLabel(a: MonitoredApplication) {
  if (a.state === 'UNCONFIGURED') return '待配置采集端点'
  return a.sensorKind ? gatewayStateLabel(a.state) : agvConnectionLabel(a.state)
}
function date(value?: number | null) { return value == null ? '—' : new Date(value).toLocaleString() }
async function refreshState() {
  const id = stateId.value, request = ++stateRequest
  if (id == null || busy.value) return
  try {
    const next = await applicationState(id)
    if (disposed || id !== stateId.value || request !== stateRequest) return
    if (state.value && (state.value.revisionId !== next.revisionId || state.value.configurationEpoch !== next.configurationEpoch)) {
      closeEditor(); notice.value = '应用点位版本已更新，请重新打开点位设置。'
    }
    state.value = next; fetchedAt.value = Date.now(); stateError.value = ''
  } catch (e) { if (!disposed && id === stateId.value && request === stateRequest) stateError.value = e instanceof Error ? e.message : String(e) }
}
async function refresh() {
  if (refreshing || disposed) return
  refreshing = true
  try {
    const [list, status] = await Promise.all([monitoredApplications(), connectionStatus()])
    if (disposed) return
    apps.value = list; slots.value = status.slots; error.value = ''
    await refreshState()
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { refreshing = false }
}
async function act(action: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false; await refresh() }
}
async function showState(app: MonitoredApplication) {
  if (!app) return
  closeEditor(); stateId.value = app.appId; state.value = undefined
  internal.value = false; search.value = ''; stateError.value = ''; notice.value = ''
  await refreshState()
}
function closeState() { if (!busy.value && !editing.value) { stateId.value = undefined; state.value = undefined; stateRequest++ } }
function openEditor(point: StatePoint) {
  if (!state.value || settingsLocked.value || !point.writable || !point.canSet) return
  editTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  editing.value = { state: state.value, field: point.field }; editError.value = ''; notice.value = ''
}
function closeEditor() {
  if (busy.value) return
  editing.value = undefined; editError.value = ''
  void nextTick(() => { if (editTrigger?.isConnected) editTrigger.focus(); editTrigger = null })
}
async function submit(value: unknown) {
  if (busy.value || editReason.value || !editing.value || !editingPoint.value || !state.value) return
  const current = editing.value.state
  busy.value = true; editError.value = ''; notice.value = ''
  try {
    const payload = stateSettingPayload(state.value, editingPoint.value, value)
    await setApplicationState(current, payload)
    busy.value = false; closeEditor(); notice.value = '设置已提交，当前状态以设备后续反馈为准。'
  } catch (e) { editError.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false; await refreshState() }
}
onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 500)
  const loop = async () => { await refresh(); if (!disposed) timer = setTimeout(loop, 2000) }
  void loop()
})
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); clearInterval(clockTimer); stateRequest++ })
</script>

<template>
  <div>
    <div class="toolbar"><h2>连接监控</h2><span class="tag muted">应用连接 {{ slots.used }} / {{ slots.max }}</span><span class="spacer"></span>
      <input v-model="filter" class="search" placeholder="搜索应用名称或编码" aria-label="搜索应用" />
      <button @click="config = {}">接入 AGV / 传感器</button><button :disabled="busy" @click="refresh">刷新</button>
    </div>
    <p class="hint">统一查看设备应用的连接与点位状态。三个传感器分别启停，共用一条网关采集连接。</p>
    <p v-if="error" class="tag err" role="alert">{{ error }}</p>
    <div class="panel table-wrap"><table><thead><tr><th>应用</th><th>协议</th><th>当前状态</th><th>最近连接心跳</th><th>操作</th></tr></thead>
      <tbody><tr v-for="app in visibleApps" :key="app.appId">
        <td>{{ app.appName }}<br /><small>{{ app.appCode }}</small></td>
        <td>{{ protocolNames[app.protocolType] || app.protocolType }}<br /><small>版本 #{{ app.revisionId || '—' }}<template v-if="app.adapterType && adapterNames[app.adapterType]"> · {{ adapterNames[app.adapterType] }}</template></small></td>
        <td><span :class="['tag', ['RUNNING', 'ONLINE'].includes(app.state) ? 'ok' : 'muted']">{{ stateLabel(app) }}</span><small v-if="!app.enabled"> · 应用已停用</small></td>
        <td>{{ date(app.lastHeartbeatAt) }}</td>
        <td class="actions"><button :disabled="busy" @click="showState(app)">状态设置</button>
          <button v-if="app.adapterType === 'seer_agv'" :disabled="busy" @click="navigation = app">导航至站点</button>
          <button v-if="!app.connected" :disabled="busy || !app.enabled || app.state === 'UNCONFIGURED'" @click="act(() => connectionOpen(app.appId))">{{ app.sensorKind ? '启动采集' : '建立连接' }}</button>
          <button v-if="app.connected" :disabled="busy" @click="act(() => connectionReconnect(app.appId))">重连</button>
          <button v-if="app.connected || app.desiredState === 'open'" :disabled="busy" @click="act(() => connectionClose(app.appId))">{{ app.sensorKind ? '停止采集' : '断开' }}</button>
          <button v-if="app.sensorKind || app.deviceType === 'agv'" @click="config = { agvAppId: app.agvAppId ?? app.appId, sensorKind: app.sensorKind }">采集配置与历史</button>
        </td>
      </tr><tr v-if="!visibleApps.length"><td colspan="5" class="muted">暂无符合条件的设备应用</td></tr></tbody>
    </table></div>
    <div v-if="stateId != null" class="modal-mask" :inert="!!editing" @click.self="closeState" @keydown.esc.stop="closeState"><section class="panel modal state-modal" role="dialog" :aria-modal="!editing" aria-label="状态设置">
      <div class="toolbar"><h3>状态设置</h3><select :value="stateId" aria-label="选择应用" :disabled="busy" @change="showState(apps.find(a => a.appId === Number(($event.target as HTMLSelectElement).value))!)"><option v-for="a in apps" :key="a.appId" :value="a.appId">{{ a.appName }}</option></select><span class="spacer"></span><button :disabled="busy" @click="closeState">关闭</button></div>
      <p class="hint">状态每 2 秒自动更新。点击点位旁的“设置”修改可写参数；只读点位展示设备反馈。</p>
      <p v-if="stateError" class="tag err" role="alert">{{ stateError }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <div class="toolbar state-tools"><input v-model="search" class="search" placeholder="搜索点位名称或编码" aria-label="搜索点位" /><label class="check"><input v-model="internal" type="checkbox" />显示内部诊断点位</label><span class="spacer"></span><button :disabled="busy" @click="refreshState">刷新状态</button></div>
      <div class="state-scroll"><PointStatePanels v-if="state" :state="state" :points="points" :now="now" :fetched-at="fetchedAt" :locked="settingsLocked" @set="openEditor" /><p v-else class="hint">正在加载点位状态…</p></div>
      <div class="toolbar footer"><small v-if="state">应用版本 #{{ state.revisionId }} · 更新于 {{ date(state.serverTime) }}</small><span class="spacer"></span><small v-if="state">{{ points.length }} 个点位</small></div>
    </section></div>
    <PointValueEditor v-if="editing && editingPoint && state" :key="editing.field" :state="state" :point="editingPoint" :allowed="!editReason" :reason="editReason" :busy="busy" :error="editError" :current-valid="pointQuality(editingPoint, state, fetchedAt, now) === 'GOOD'" @close="closeEditor" @submit="submit" />
    <div v-if="config" class="modal-mask" @click.self="config = undefined"><section class="panel modal config-modal" role="dialog" aria-label="采集配置与历史"><div class="toolbar"><h3>采集配置与历史</h3><span class="spacer"></span><button @click="config = undefined; refresh()">关闭</button></div><AgvCollectionPanel :agv-app-id="config.agvAppId" :sensor-kind="config.sensorKind" @changed="refresh" /></section></div>
  </div>
  <AgvNavigationPanel v-if="navigation" :app-id="navigation.appId" :app-name="navigation.appName" @close="navigation = undefined; refresh()" />
</template>

<style scoped>
h2,h3 { margin: 0; } .state-modal { width: calc(100vw - 16px); height: calc(100vh - 16px); max-height: calc(100vh - 16px); display: flex; flex-direction: column; overflow: hidden; padding: 16px; border-radius: 8px; } .config-modal { width: min(1100px, 96vw); max-height: 94vh; overflow: auto; }
.state-modal .toolbar select { width: 300px; } .hint,small,.muted { color: var(--muted); } .hint { font-size: 12px; line-height: 1.6; }
.search { max-width: 260px; } .table-wrap { overflow: auto; } .state-scroll { flex: 1; overflow: auto; min-height: 0; background: #fafbfc; border: 1px solid var(--border); border-radius: 6px; padding: 12px; }
.state-tools { margin-top: 0; } .state-modal > .toolbar, .state-modal > p { flex-shrink: 0; }
.state-modal > .toolbar { margin-bottom: 8px; } .state-modal > .hint { margin: 0 0 8px; line-height: 1.5; }
.state-modal h3 { margin: 0; font-size: 18px; }
.check { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; } input[type="checkbox"] { width: auto; } .actions { min-width: 290px; }
.state-modal > .footer { margin: 8px 0 0; }
@media (max-width: 600px) { .state-modal { padding: 10px; } .state-modal .toolbar select { width: min(230px, 100%); } .state-scroll { padding: 8px; } }
</style>
