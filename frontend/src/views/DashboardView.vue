<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  connectionStatus, alarmActive,
  type ConnectionSnapshot, type SlotUsage, type Alarm,
} from '../api/wcs'

const conns = ref<ConnectionSnapshot[]>([])
const slots = ref<SlotUsage>({ max: 0, used: 0 })
const alarms = ref<Alarm[]>([])
const wsConnected = ref(false)
let timer: number | undefined
let ws: WebSocket | undefined

const runningCount = computed(() => conns.value.filter((c) => c.state === 'RUNNING').length)

function num(c: ConnectionSnapshot, field: string): string {
  const v = c.latest?.[field]
  if (v === undefined || v === null) return '-'
  return typeof v === 'number' ? String(v) : String(v)
}
function stateClass(s: string) {
  if (s === 'RUNNING' || s === 'CONNECTED') return 'ok'
  if (s === 'DISCONNECTED' || s === 'FAILED') return 'err'
  return 'warn'
}
function levelClass(l: string) {
  return l === 'warn' ? 'warn' : l === 'info' ? 'muted' : 'err'
}

async function poll() {
  try {
    const st = await connectionStatus()
    conns.value = st.connections
    slots.value = st.slots
    alarms.value = await alarmActive()
  } catch { /* ignore transient */ }
}

function connectWs() {
  try {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    ws = new WebSocket(`${proto}://${location.host}/ws/monitor`)
    ws.onopen = () => (wsConnected.value = true)
    ws.onclose = () => (wsConnected.value = false)
    ws.onerror = () => (wsConnected.value = false)
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data)
        if (msg.connections) conns.value = msg.connections
        if (msg.slots) slots.value = msg.slots
        if (msg.alarms) alarms.value = msg.alarms
      } catch { /* ignore */ }
    }
  } catch { /* ws optional */ }
}

onMounted(() => {
  poll()
  timer = window.setInterval(poll, 2000) // 轮询兜底，WS 推送更快
  connectWs()
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
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
      <div class="panel stat"><div class="n" style="color:var(--err)">{{ alarms.length }}</div><div class="l">活动报警</div></div>
      <div class="panel stat"><div class="n">{{ slots.used }}/{{ slots.max }}</div><div class="l">连接槽</div></div>
    </div>

    <div class="layout">
      <div class="devices">
        <div v-for="c in conns" :key="c.appId" class="panel device">
          <div class="dev-head">
            <strong>{{ c.appCode }}</strong>
            <span class="muted">{{ c.appName }}</span>
            <span class="spacer"></span>
            <span :class="['tag', stateClass(c.state)]">{{ c.state }}</span>
          </div>
          <div class="metrics">
            <div><span>PLC心跳</span><b>{{ num(c, 'PLC_Heart') }}</b></div>
            <div><span>模式</span><b>{{ num(c, 'status_Mode') }}</b></div>
            <div><span>任务状态</span><b>{{ num(c, 'status_Task') }}</b></div>
            <div><span>故障码</span><b>{{ num(c, 'status_ErrorCode') }}</b></div>
            <div><span>当前列</span><b>{{ num(c, 'status_CurrentColumnNum') }}</b></div>
            <div><span>当前层</span><b>{{ num(c, 'status_CurrentFloorNum') }}</b></div>
            <div><span>行走速度</span><b>{{ num(c, 'status_Speed_Walk') }}</b></div>
            <div><span>提升速度</span><b>{{ num(c, 'status_Speed_Lift') }}</b></div>
          </div>
        </div>
        <div v-if="!conns.length" class="panel" style="padding:24px;color:var(--muted)">
          暂无连接。请到「连接监控」建立连接。
        </div>
      </div>

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
    </div>
  </div>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.stat { padding: 16px; text-align: center; }
.stat .n { font-size: 28px; font-weight: 700; }
.stat .l { color: var(--muted); margin-top: 2px; }
.layout { display: grid; grid-template-columns: 1fr 320px; gap: 14px; }
.devices { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; align-content: start; }
.device { padding: 12px 14px; }
.dev-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 14px; }
.metrics > div { display: flex; justify-content: space-between; border-bottom: 1px dashed var(--border); padding: 3px 0; }
.metrics span { color: var(--muted); }
.alarms { padding: 0; align-self: start; }
.alarm-head { padding: 12px 14px; font-weight: 600; border-bottom: 1px solid var(--border); }
.alarm-row { display: flex; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--border); align-items: flex-start; }
.alarm-msg { min-width: 0; }
.muted { color: var(--muted); }
</style>
