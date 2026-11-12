<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { agvCreate, agvList, agvUpgrade, gatewayApplications, gatewayList, gatewayConfigure, gatewayEnable, gatewayPower, telemetryHistory, telemetryLatest,
  type AgvState, type Gateway, type Telemetry, type Cycle, type Measurement } from '../api/agv'
import { gatewayStateLabel, storageStateLabel, agvQualityLabel } from '../utils/agvLabels'

const vehicles = ref<AgvState[]>([])
const props = defineProps<{ agvAppId?: number; sensorKind?: string }>()
const emit = defineEmits<{ changed: [] }>()
const selectedId = ref<number | undefined>(props.agvAppId)
const vehicle = computed(() => vehicles.value.find(item => item.appId === selectedId.value))
const gateway = ref<Gateway>()
const telemetry = ref<Telemetry>()
const history = ref<Cycle[]>([])
const error = ref('')
const busy = ref(false)
const refreshing = ref(false)
const showCreate = ref(false)
const showConfigure = ref(false)
const createForm = reactive({ appCode: '', appName: '', endpointRef: '' })
const gatewayForm = reactive({ endpointRef: '', infraredOffset: 0, infraredMin: 0, infraredMax: 600 })
const supplyAt = ref('')
const supplyEvidence = ref('')
const metric = ref(props.sensorKind === 'vibration_sensor' ? 'vibration_speed_x' : props.sensorKind === 'infrared_sensor' ? 'infrared_temperature' : 'noise')
let disposed = false
let refreshTimer: ReturnType<typeof setTimeout>
const labels: Record<string, string> = {
  vibration_speed_x: 'X 轴振动速度', vibration_speed_y: 'Y 轴振动速度', vibration_speed_z: 'Z 轴振动速度',
  vibration_chip_temperature: '振动芯片温度', vibration_displacement_x: 'X 轴位移', vibration_displacement_y: 'Y 轴位移',
  vibration_displacement_z: 'Z 轴位移', vibration_frequency_x: 'X 轴频率', vibration_frequency_y: 'Y 轴频率',
  vibration_frequency_z: 'Z 轴频率', noise: '噪声', infrared_temperature: '红外温度',
}
const metricLabels = computed(() => Object.fromEntries(Object.entries(labels).filter(([key]) => !props.sensorKind || (props.sensorKind === 'vibration_sensor' ? key.startsWith('vibration_') : props.sensorKind === 'noise_sensor' ? key === 'noise' : key === 'infrared_temperature'))))
function date(value?: number | null) { return value == null ? '—' : new Date(value).toLocaleString() }
function value(sample?: Measurement | null) { return sample?.value == null ? '—' : Number(sample.value.toFixed(3)).toString() }
const chart = computed(() => {
  const rows = [...history.value].reverse()
  const valid = rows.flatMap(row => { const s = row.measurements[metric.value]; return s?.quality === 'GOOD' && s.value != null ? [s.value] : [] })
  if (!valid.length || rows.length < 2) return { segments: [] as string[], min: '', max: '' }
  const min = Math.min(...valid), max = Math.max(...valid), span = max - min || 1
  const start = rows[0]!.finished_at, duration = rows[rows.length - 1]!.finished_at - start || 1
  const segments: string[] = []
  let segment: string[] = [], previous = 0
  for (const row of rows) {
    const sample = row.measurements[metric.value]
    if (!sample || sample.quality !== 'GOOD' || sample.value == null || previous && row.finished_at - previous > 6000) {
      if (segment.length) segments.push(segment.join(' '))
      segment = []
    }
    if (sample?.quality === 'GOOD' && sample.value != null) {
      segment.push(`${20 + (row.finished_at - start) / duration * 760},${max === min ? 90 : 160 - (sample.value - min) / span * 140}`)
    }
    previous = row.finished_at
  }
  if (segment.length) segments.push(segment.join(' '))
  return { segments, min: min.toFixed(2), max: max.toFixed(2) }
})
async function refresh() {
  if (refreshing.value || disposed) return
  refreshing.value = true
  const id = selectedId.value
  try {
    const list = await agvList()
    if (disposed) return
    vehicles.value = list
    if (!selectedId.value && list.length) { selectedId.value = list[0]!.appId; return }
    if (!id) return
    const configs = await gatewayList(id)
    if (disposed || selectedId.value !== id) return
    gateway.value = configs[0]
    if (configs[0]) {
      const data = await telemetryLatest(configs[0].id)
      if (disposed || selectedId.value !== id) return
      telemetry.value = data
    } else telemetry.value = undefined
    error.value = ''
  } catch (ex) { if (!disposed) error.value = ex instanceof Error ? ex.message : String(ex) }
  finally { refreshing.value = false }
}
async function act(action: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action(); await refresh(); emit('changed') }
  catch (ex) { error.value = ex instanceof Error ? ex.message : String(ex) }
  finally { busy.value = false }
}
async function create() { await act(async () => { const item = await agvCreate(createForm); selectedId.value = item.appId; showCreate.value = false }) }
function editGateway() {
  const g = gateway.value
  if (g) Object.assign(gatewayForm, { endpointRef: g.endpoint_ref, infraredOffset: g.infrared_offset, infraredMin: g.infrared_min, infraredMax: g.infrared_max })
  showConfigure.value = !showConfigure.value
}
async function saveGateway() {
  if (!selectedId.value) return
  await act(async () => {
    const configured = await gatewayConfigure({ ...gatewayForm, agvAppId: selectedId.value!, id: gateway.value?.id, expectedVersion: gateway.value?.config_version })
    await gatewayApplications(configured.id)
    showConfigure.value = false; history.value = []
  })
}
async function savePower(unknown = false) {
  if (!gateway.value) return
  if (!unknown && (!supplyAt.value || !supplyEvidence.value.trim())) { error.value = '请填写可信上电时间和来源说明'; return }
  await act(() => gatewayPower(gateway.value!, unknown ? null : new Date(supplyAt.value).getTime(), supplyEvidence.value))
}
async function loadHistory(older = false) {
  const current = gateway.value
  if (!current) return
  await act(async () => {
    const result = await telemetryHistory(current.id, older ? history.value[history.value.length - 1]?.id : undefined)
    if (gateway.value?.id === current.id) history.value = result.rows
  })
}
watch(selectedId, () => { gateway.value = undefined; telemetry.value = undefined; history.value = []; showConfigure.value = false; void refresh() })
onMounted(() => {
  void refresh()
  const loop = async () => { await refresh(); if (!disposed) refreshTimer = setTimeout(loop, 2000) }
  refreshTimer = setTimeout(loop, 2000)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(refreshTimer) })
</script>

<template>
  <div class="agv-page">
    <div class="heading"><div><h3>车辆与传感器采集档案</h3><p>应用及实时点位统一在连接监控的“状态设置”中查看。</p></div><button v-if="!props.agvAppId" :disabled="busy" @click="showCreate = !showCreate">新增车辆</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="showCreate" class="panel fields" @submit.prevent="create">
      <label>车辆编码<input v-model="createForm.appCode" required maxlength="48" pattern="[A-Za-z][A-Za-z0-9_-]*" /></label>
      <label>车辆名称<input v-model="createForm.appName" required maxlength="100" /></label>
      <label>仙工状态端点引用<input v-model="createForm.endpointRef" required placeholder="运维配置的引用名" /></label>
      <button :disabled="busy" type="submit">创建只读车辆</button>
    </form>
    <div class="toolbar">
      <label>所属车辆 <select v-model="selectedId" :disabled="!!props.agvAppId"><option v-for="item in vehicles" :key="item.appId" :value="item.appId">{{ item.appName }} · {{ item.appCode }}</option></select></label>
      <button :disabled="refreshing" @click="refresh">刷新</button>
      <span v-if="refreshing" class="muted">更新中</span>
    </div>
    <p v-if="!vehicles.length" class="panel muted">先配置运行环境中的设备端点，再添加车辆。每车可配置一个三传感器网关。</p>
    <template v-if="vehicle">
      <p class="muted">仙工位置、速度与导航状态均为只读点位。<button :disabled="busy" @click="act(() => agvUpgrade(vehicle!.appId))">同步 AGV 状态点位</button></p>
      <div class="panel">
        <div class="heading"><h3>三传感器网关</h3><div class="toolbar"><span class="badge" :title="telemetry?.connectionState">{{ gatewayStateLabel(telemetry?.connectionState) }}</span>
          <button :disabled="busy || gateway?.enabled || !!telemetry?.activeUnits?.length" @click="editGateway">{{ gateway ? '编辑采集档案' : '配置网关' }}</button>
          <button v-if="gateway && !telemetry?.applicationManaged && gateway.enabled" :disabled="busy" @click="act(() => gatewayEnable(gateway!, false))">停止原网关采集</button>
          <button v-if="gateway && !telemetry?.applicationManaged" :disabled="busy || gateway.enabled" @click="act(() => gatewayApplications(gateway!.id))">创建三个传感器应用</button></div></div>
        <form v-if="showConfigure" class="fields" @submit.prevent="saveGateway">
          <label>采集端点引用<input v-model="gatewayForm.endpointRef" required placeholder="运维配置的引用名" /></label>
          <label>红外零基寄存器<select v-model.number="gatewayForm.infraredOffset"><option :value="0">0x0000</option><option :value="1">0x0001</option></select></label>
          <label>档案量程下限 °C<input v-model.number="gatewayForm.infraredMin" type="number" min="0" step="0.1" required /></label>
          <label>档案量程上限 °C<input v-model.number="gatewayForm.infraredMax" type="number" max="6553.5" step="0.1" required /></label>
          <button type="submit" :disabled="busy">保存档案</button>
        </form>
        <template v-if="gateway">
          <p class="muted">说明书默认：站号 80 / 1 / 2，红外寄存器 0x0000、量程 0–600℃；装机时按实际型号核实档案。</p>
          <p v-if="telemetry && !telemetry.endpointConfigured" class="muted">已保存采集档案，等待运维为该端点引用配置网关地址。</p>
          <p class="muted">目标每 2 秒一轮；超过 6 秒未更新则标记为数据已过期。红外预热需 40 分钟，网络重连不重置供电记录。</p>
          <details><summary>红外供电记录：{{ date(gateway.power_on_at) }}</summary>
            <p>{{ gateway.power_evidence || '尚无可信供电记录，温度不作为有效测量。' }}</p>
            <div class="fields"><label>确认的上电时间（本地时间）<input v-model="supplyAt" type="datetime-local" /></label><label>供电记录来源<input v-model="supplyEvidence" maxlength="500" placeholder="供电事件或现场确认记录" /></label>
              <button :disabled="busy" @click="savePower()">保存供电记录</button><button :disabled="busy" @click="savePower(true)">标记供电未知</button></div>
          </details>
          <p v-if="telemetry" class="muted">数据存储：{{ storageStateLabel(telemetry.storage.persistence) }} · 待写入 {{ telemetry.storage.queued }} / 60 轮 · 已丢弃 {{ telemetry.storage.dropped }} 轮<span v-if="telemetry.diagnostics"> · 本轮耗时 {{ telemetry.diagnostics.durationMs }} ms · 连续失败 {{ telemetry.diagnostics.consecutiveFailures }} 次</span></p>
        </template>
      </div>
      <div v-if="gateway" class="panel">
        <div class="heading"><h3>连续采集历史</h3><div class="toolbar"><select v-model="metric"><option v-for="(label, key) in metricLabels" :key="key" :value="key">{{ label }}</option></select><button :disabled="busy" @click="loadHistory()">最近 100 轮</button><button :disabled="busy || history.length < 100" @click="loadHistory(true)">更早一页</button></div></div>
        <p class="muted">仅连接有效测量；无效、缺测或间隔超过 6 秒时断开曲线。历史不会自动补造。</p>
        <template v-if="history.length"><div class="chart-labels"><span>{{ chart.max }}</span><span>{{ chart.min }}</span></div>
          <svg viewBox="0 0 800 180" role="img" :aria-label="labels[metric] + '历史趋势'"><path d="M20 160 H780" stroke="#d7dde5" /><polyline v-for="(segment, index) in chart.segments" :key="index" :points="segment" fill="none" stroke="#df7b24" stroke-width="2" /></svg>
          <div class="heading muted"><span>{{ date(history[history.length - 1]?.finished_at) }}</span><span>{{ date(history[0]?.finished_at) }}</span></div>
          <table><thead><tr><th>接收时间</th><th>测量值</th><th>数据质量</th><th>本轮耗时</th></tr></thead><tbody><tr v-for="row in history.slice(0, 20)" :key="row.id"><td>{{ date(row.finished_at) }}</td><td>{{ row.measurements[metric]?.quality === 'GOOD' ? value(row.measurements[metric]) : '—' }} {{ row.measurements[metric]?.unit }}</td><td>{{ agvQualityLabel(row.measurements[metric]?.quality) }}</td><td>{{ row.duration_ms }} ms</td></tr></tbody></table>
        </template><p v-else class="muted">点击“最近 100 轮”加载历史。</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.agv-page { display: grid; gap: 16px; }
h2, h3, p { margin: 0; }
h2 { margin-bottom: 6px; } h3 { font-size: 16px; }
p { line-height: 1.6; } .heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.panel { border: 1px solid var(--border, #d7dde5); border-radius: 8px; padding: 18px; background: var(--panel, white); display: grid; gap: 12px; }
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.fields { display: flex; flex-wrap: wrap; gap: 14px; align-items: end; }
.fields label { display: grid; gap: 5px; font-size: 13px; }
input, select { max-width: 100%; padding: 7px 9px; border: 1px solid var(--border, #d7dde5); border-radius: 5px; background: var(--panel, white); }
button { padding: 7px 11px; cursor: pointer; } button:disabled { opacity: .5; cursor: default; }
.muted, .heading > div > p { color: var(--muted, #697586); font-size: 12px; }
.badge { font-size: 11px; border-radius: 5px; padding: 4px 7px; background: #edf0f3; color: #374151; }
.good { color: #16734a; background: #e6f4ec; } .warn { color: #9c560f; background: #fff1dd; }
.error { color: #ad2525; padding: 10px 14px; background: #fff0f0; border-radius: 6px; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(275px, 1fr)); gap: 12px; }
.query-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 24px; }
.reading { font-size: 30px; font-variant-numeric: tabular-nums; } .reading small { font-size: 14px; color: var(--muted); }
dl { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 6px 12px; font-size: 13px; } dt { color: var(--muted); overflow-wrap: anywhere; } dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; } details p { margin: 8px 0; }
pre { font-size: 11px; max-height: 220px; overflow: auto; white-space: pre-wrap; }
svg { width: 100%; max-height: 220px; } .chart-labels { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; }
table { width: 100%; text-align: left; border-collapse: collapse; font-size: 13px; } th, td { padding: 8px; border-bottom: 1px solid var(--border); }
</style>
