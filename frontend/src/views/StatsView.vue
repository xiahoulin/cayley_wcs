<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  statsTaskSummary, statsDeviceUtilization, statsAlarmTop, applicationAll,
  type TaskSummary, type DeviceUtilization, type AlarmTop, type Application,
} from '../api/wcs'

const apps = ref<Application[]>([])
const appId = ref<number | null>(null)
const fromStr = ref('')
const toStr = ref('')
const error = ref('')
const loading = ref(false)

const summary = ref<TaskSummary | null>(null)
const util = ref<DeviceUtilization[]>([])
const alarmTop = ref<AlarmTop[]>([])

function millis(s: string) { return s ? new Date(s).getTime() : 0 }
function pct(v: number) { return (v * 100).toFixed(1) + '%' }
function secs(ms: number) { return (ms / 1000).toFixed(1) + 's' }
function levelClass(l: string) { return l === 'warn' ? 'warn' : l === 'info' ? 'muted' : 'err' }
function appName(id: number) { return apps.value.find((a) => a.id === id)?.app_name || `#${id}` }

async function load() {
  error.value = ''
  loading.value = true
  const f = millis(fromStr.value)
  const t = millis(toStr.value)
  try {
    if (!apps.value.length) apps.value = await applicationAll()
    const [s, u, a] = await Promise.all([
      statsTaskSummary(appId.value, f, t),
      statsDeviceUtilization(appId.value, f, t),
      statsAlarmTop(appId.value, f, t, 10),
    ])
    summary.value = s
    util.value = u
    alarmTop.value = a
  } catch (e: any) { error.value = e?.message || '加载失败' } finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">运行统计</h2>
      <select v-model.number="appId" style="width:170px;margin-left:12px">
        <option :value="null">全部设备</option>
        <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}</option>
      </select>
      <label class="rng">起 <input type="datetime-local" v-model="fromStr" /></label>
      <label class="rng">止 <input type="datetime-local" v-model="toStr" /></label>
      <span class="spacer"></span>
      <button class="primary" :disabled="loading" @click="load">{{ loading ? '查询中…' : '查询' }}</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div v-if="summary" class="cards">
      <div class="panel stat"><div class="n">{{ summary.total }}</div><div class="l">任务总量</div></div>
      <div class="panel stat"><div class="n" style="color:var(--ok)">{{ summary.completed }}</div><div class="l">已完成</div></div>
      <div class="panel stat"><div class="n" style="color:var(--err)">{{ summary.failed }}</div><div class="l">失败</div></div>
      <div class="panel stat"><div class="n" style="color:var(--muted)">{{ summary.cancelled }}</div><div class="l">已取消</div></div>
      <div class="panel stat"><div class="n" style="color:var(--warn)">{{ summary.inFlight }}</div><div class="l">在飞</div></div>
      <div class="panel stat"><div class="n">{{ summary.pending }}</div><div class="l">排队</div></div>
      <div class="panel stat"><div class="n">{{ pct(summary.completionRate) }}</div><div class="l">完成率</div></div>
      <div class="panel stat"><div class="n">{{ secs(summary.avgExecMillis) }}</div><div class="l">平均时长</div></div>
    </div>

    <div class="grid">
      <div class="panel">
        <div class="ph">设备利用率</div>
        <table>
          <thead><tr><th>设备</th><th>运行时长</th><th>窗口时长</th><th>利用率</th></tr></thead>
          <tbody>
            <tr v-for="u in util" :key="u.appId">
              <td>{{ u.appCode || appName(u.appId) }}</td>
              <td>{{ secs(u.runningMillis) }}</td>
              <td>{{ secs(u.windowMillis) }}</td>
              <td>
                <div class="bar"><div class="fill" :style="{ width: pct(u.utilization) }"></div></div>
                <small class="muted">{{ pct(u.utilization) }}</small>
              </td>
            </tr>
            <tr v-if="!util.length"><td colspan="4" style="color:var(--muted)">无数据</td></tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <div class="ph">报警 TOP</div>
        <table>
          <thead><tr><th>故障码</th><th>级别</th><th>次数</th><th>最近信息</th></tr></thead>
          <tbody>
            <tr v-for="a in alarmTop" :key="a.faultCode">
              <td><code>{{ a.faultCode }}</code></td>
              <td><span :class="['tag', levelClass(a.level)]">{{ a.level }}</span></td>
              <td>{{ a.count }}</td>
              <td style="white-space:normal">{{ a.latestMessage }}</td>
            </tr>
            <tr v-if="!alarmTop.length"><td colspan="4" style="color:var(--muted)">无报警</td></tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <div class="ph">失败故障码 TOP</div>
        <table>
          <thead><tr><th>故障码</th><th>失败次数</th></tr></thead>
          <tbody>
            <tr v-for="f in summary?.failTop || []" :key="f.errorCode">
              <td><code>{{ f.errorCode }}</code></td>
              <td>{{ f.count }}</td>
            </tr>
            <tr v-if="!summary?.failTop?.length"><td colspan="2" style="color:var(--muted)">无失败记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rng { display: inline-flex; align-items: center; gap: 4px; color: var(--muted); font-size: 12px; margin-left: 8px; }
.rng input { width: 190px; }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.stat { padding: 14px; text-align: center; }
.stat .n { font-size: 24px; font-weight: 700; }
.stat .l { color: var(--muted); margin-top: 2px; font-size: 12px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
.grid .panel:first-child { grid-column: 1 / -1; }
.ph { padding: 10px 12px; font-weight: 600; border-bottom: 1px solid var(--border); }
.bar { display: inline-block; width: 120px; height: 8px; background: #eef0f2; border-radius: 4px; overflow: hidden; vertical-align: middle; margin-right: 6px; }
.fill { height: 100%; background: var(--primary); }
.muted { color: var(--muted); }
</style>
