<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  connectionStatus, connectionOpen, connectionClose, connectionReconnect, applicationList,
  type ConnectionSnapshot, type SlotUsage, type Application,
} from '../api/wcs'

const conns = ref<ConnectionSnapshot[]>([])
const slots = ref<SlotUsage>({ max: 0, used: 0 })
const apps = ref<Application[]>([])
const selectedApp = ref<number | null>(null)
const error = ref('')
let timer: number | undefined

function stateClass(s: string) {
  if (s === 'RUNNING' || s === 'CONNECTED') return 'ok'
  if (s === 'DISCONNECTED' || s === 'FAILED') return 'err'
  return 'warn'
}
function ago(ms: number) {
  if (!ms) return '-'
  const s = Math.round((Date.now() - ms) / 1000)
  return s < 60 ? `${s}s 前` : `${Math.round(s / 60)}m 前`
}

async function refresh() {
  try {
    const st = await connectionStatus()
    conns.value = st.connections
    slots.value = st.slots
  } catch (e: any) {
    error.value = e?.message || ''
  }
}
async function open() {
  if (!selectedApp.value) return
  error.value = ''
  try {
    await connectionOpen(selectedApp.value)
    await refresh()
  } catch (e: any) {
    error.value = e?.message || '建立连接失败'
  }
}
async function close(id: number) {
  await connectionClose(id)
  await refresh()
}
async function reconnect(id: number) {
  try { await connectionReconnect(id) } catch (e: any) { error.value = e?.message || '' }
  await refresh()
}

onMounted(async () => {
  apps.value = (await applicationList()).rows
  selectedApp.value = apps.value[0]?.id ?? null
  await refresh()
  timer = window.setInterval(refresh, 2000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">连接监控</h2>
      <span class="tag muted">连接槽 {{ slots.used }} / {{ slots.max }}</span>
      <span class="spacer"></span>
      <select v-model="selectedApp" style="width:auto">
        <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_code }} · {{ a.app_name }}</option>
      </select>
      <button class="primary" @click="open">建立连接</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>应用</th><th>协议</th><th>状态</th><th>最近心跳</th><th>重试</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in conns" :key="c.appId">
            <td>{{ c.appCode }}<br /><small style="color:var(--muted)">{{ c.appName }}</small></td>
            <td><span class="tag muted">{{ c.protocolType }}</span></td>
            <td><span :class="['tag', stateClass(c.state)]">{{ c.state }}</span></td>
            <td>{{ ago(c.lastHeartbeatAt) }}</td>
            <td>{{ c.retryCount }}</td>
            <td>
              <button @click="reconnect(c.appId)">重连</button>
              <button class="danger" @click="close(c.appId)">断开</button>
            </td>
          </tr>
          <tr v-if="!conns.length"><td colspan="6" style="color:var(--muted)">当前无连接</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
