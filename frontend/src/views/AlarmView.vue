<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  alarmList, alarmActive, alarmAck, alarmClear, alarmResetFault,
  applicationAll, type Alarm, type Application,
} from '../api/wcs'

const rows = ref<Alarm[]>([])
const apps = ref<Application[]>([])
const error = ref('')
const tab = ref<'active' | 'history'>('active')
const resetAppId = ref<number | null>(null)

function appName(id: number) {
  return apps.value.find((a) => a.id === id)?.app_name || `#${id}`
}
function levelClass(l: string) {
  return l === 'warn' ? 'warn' : l === 'info' ? 'muted' : 'err'
}
function statusClass(s: string) {
  if (s === 'raised') return 'err'
  if (s === 'ack') return 'warn'
  return 'muted'
}
function statusLabel(s: string) {
  if (s === 'raised') return '未处理'
  if (s === 'ack') return '已确认'
  if (s === 'cleared') return '已清除'
  return s
}
function canAck(s: string) { return s === 'raised' }
function canClear(s: string) { return s !== 'cleared' }

const shown = computed(() =>
  tab.value === 'active' ? rows.value.filter((r) => r.status !== 'cleared') : rows.value,
)

async function load() {
  error.value = ''
  try {
    apps.value = await applicationAll()
    rows.value = tab.value === 'active' ? await alarmActive() : (await alarmList()).rows
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  }
}
function switchTab(t: 'active' | 'history') {
  tab.value = t
  load()
}
async function ack(a: Alarm) {
  try { await alarmAck(a.id); await load() } catch (e: any) { error.value = e?.message || '确认失败' }
}
async function clear(a: Alarm) {
  if (!confirm(`清除报警「${a.message}」？`)) return
  try { await alarmClear(a.id); await load() } catch (e: any) { error.value = e?.message || '清除失败' }
}
async function resetFault() {
  if (!resetAppId.value) { error.value = '请选择要复位的设备应用'; return }
  if (!confirm(`向应用「${appName(resetAppId.value)}」下发故障复位脉冲？`)) return
  try { await alarmResetFault(resetAppId.value); await load() } catch (e: any) { error.value = e?.message || '复位失败' }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">报警中心</h2>
      <div class="tabs">
        <button :class="{ primary: tab === 'active' }" @click="switchTab('active')">活动报警</button>
        <button :class="{ primary: tab === 'history' }" @click="switchTab('history')">历史</button>
      </div>
      <span class="spacer"></span>
      <select v-model.number="resetAppId" style="width:180px">
        <option :value="null">选择设备…</option>
        <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}</option>
      </select>
      <button @click="resetFault">故障复位</button>
      <button @click="load">刷新</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>级别</th><th>设备</th><th>故障码</th><th>报警信息</th><th>处理建议</th><th>状态</th><th>触发时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in shown" :key="a.id">
            <td><span :class="['tag', levelClass(a.level)]">{{ a.level }}</span></td>
            <td>{{ appName(a.app_id) }}</td>
            <td><code>{{ a.fault_code }}</code></td>
            <td>{{ a.message }}</td>
            <td style="white-space:normal;color:var(--muted)">{{ a.suggestion }}</td>
            <td><span :class="['tag', statusClass(a.status)]">{{ statusLabel(a.status) }}</span></td>
            <td>{{ a.raised_time }}</td>
            <td>
              <button v-if="canAck(a.status)" @click="ack(a)">确认</button>
              <button v-if="canClear(a.status)" class="danger" @click="clear(a)">清除</button>
            </td>
          </tr>
          <tr v-if="!shown.length"><td colspan="8" style="color:var(--muted)">无报警</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 4px; margin-left: 14px; }
</style>
