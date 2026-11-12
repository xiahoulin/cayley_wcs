<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  taskList, taskDetail, taskCancel, taskRetry, taskReprioritize, taskPauseApp, taskResumeApp,
  chainList, chainDetail, chainCancel, applicationAll,
  type Task, type TaskChain, type Application,
} from '../api/wcs'

const tab = ref<'task' | 'chain'>('task')
const tasks = ref<Task[]>([])
const chains = ref<TaskChain[]>([])
const apps = ref<Application[]>([])
const error = ref('')
const pauseAppId = ref<number | null>(null)

const detail = ref<{ chain: TaskChain; tasks: Task[] } | null>(null)
const taskInfo = ref<Task | null>(null)
const pretty = (v: unknown) => JSON.stringify(v ?? null, null, 2)
async function showTask(t: Task) {
  try { taskInfo.value = await taskDetail(t.id) } catch (e: any) { error.value = e?.message || '任务详情加载失败' }
}

const TERMINAL = ['completed', 'failed', 'cancelled']
function appName(id?: number) {
  if (id == null) return '-'
  return apps.value.find((a) => a.id === id)?.app_name || `#${id}`
}
function statusClass(s: string) {
  if (s === 'completed') return 'ok'
  if (s === 'failed') return 'err'
  if (s === 'cancelled') return 'muted'
  if (s === 'executing' || s === 'running' || s === 'dispatched') return 'warn'
  return 'muted'
}
function isTerminal(s: string) { return TERMINAL.includes(s) }

async function load() {
  error.value = ''
  try {
    apps.value = await applicationAll()
    if (tab.value === 'task') tasks.value = (await taskList()).rows
    else chains.value = (await chainList()).rows
  } catch (e: any) { error.value = e?.message || '加载失败' }
}
function switchTab(t: 'task' | 'chain') { tab.value = t; load() }

async function act(fn: () => Promise<unknown>, ok = '') {
  try { await fn(); if (ok) error.value = ''; await load() } catch (e: any) { error.value = e?.message || '操作失败' }
}
function cancelTask(t: Task) { if (confirm(`取消任务 ${t.task_no}？`)) act(() => taskCancel(t.id)) }
function retryTask(t: Task) { act(() => taskRetry(t.id)) }
function reprioritize(t: Task) {
  const v = prompt('新的优先级（数值越大越优先）', String(t.priority))
  if (v == null) return
  const p = Number(v)
  if (Number.isNaN(p)) { error.value = '优先级必须是数字'; return }
  act(() => taskReprioritize(t.id, p))
}
function pauseApp() {
  if (!pauseAppId.value) { error.value = '请选择设备应用'; return }
  act(() => taskPauseApp(pauseAppId.value as number))
}
function resumeApp() {
  if (!pauseAppId.value) { error.value = '请选择设备应用'; return }
  act(() => taskResumeApp(pauseAppId.value as number))
}
function cancelChain(c: TaskChain) { if (confirm(`取消任务链 ${c.chain_no}？`)) act(() => chainCancel(c.id)) }
async function showChain(c: TaskChain) {
  try { detail.value = await chainDetail(c.id) } catch (e: any) { error.value = e?.message || '加载链详情失败' }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">任务队列</h2>
      <div class="tabs">
        <button :class="{ primary: tab === 'task' }" @click="switchTab('task')">任务</button>
        <button :class="{ primary: tab === 'chain' }" @click="switchTab('chain')">任务链</button>
      </div>
      <span class="spacer"></span>
      <template v-if="tab === 'task'">
        <select v-model.number="pauseAppId" style="width:170px">
          <option :value="null">选择设备…</option>
          <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}</option>
        </select>
        <button @click="pauseApp">暂停派工</button>
        <button @click="resumeApp">恢复派工</button>
      </template>
      <button @click="load">刷新</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div v-if="tab === 'task'" class="panel">
      <table>
        <thead>
          <tr><th>任务号</th><th>设备</th><th>类型</th><th>状态</th><th>握手步</th><th>优先级</th><th>链/序</th><th>重试</th><th>故障码</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td>{{ t.task_no }}</td>
            <td>{{ appName(t.app_id) }}</td>
            <td>{{ t.payload?.cmd_TaskType ?? t.task_type ?? '-' }}</td>
            <td><span :class="['tag', statusClass(t.status)]">{{ t.status }}</span></td>
            <td><small class="muted">{{ t.handshake_step || '-' }}</small></td>
            <td>{{ t.priority }}</td>
            <td>{{ t.chain_id ? `#${t.chain_id}/${t.seq_no}` : '-' }}</td>
            <td>{{ t.retry_count ?? 0 }}</td>
            <td><code v-if="t.error_code">{{ t.error_code }}</code><span v-else>-</span></td>
            <td>
              <button @click="showTask(t)">点位与结果</button>
              <button v-if="!isTerminal(t.status)" @click="reprioritize(t)">改优先级</button>
              <button v-if="!isTerminal(t.status)" class="danger" @click="cancelTask(t)">取消</button>
              <button v-if="t.status === 'failed' && !t.chain_id" @click="retryTask(t)">重试</button>
            </td>
          </tr>
          <tr v-if="!tasks.length"><td colspan="10" style="color:var(--muted)">暂无任务</td></tr>
        </tbody>
      </table>
    </div>

    <div v-else class="panel">
      <table>
        <thead>
          <tr><th>链号</th><th>下发应用</th><th>状态</th><th>选机策略</th><th>优先级</th><th>错误</th><th>完成时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in chains" :key="c.id">
            <td>{{ c.chain_no }}</td>
            <td>{{ appName(c.upstream_app_id) }}</td>
            <td><span :class="['tag', statusClass(c.status)]">{{ c.status }}</span></td>
            <td>{{ c.select_strategy }}</td>
            <td>{{ c.priority }}</td>
            <td style="white-space:normal;color:var(--err)">{{ c.error_message || '' }}</td>
            <td>{{ c.finish_time || '-' }}</td>
            <td>
              <button @click="showChain(c)">段详情</button>
              <button v-if="!isTerminal(c.status)" class="danger" @click="cancelChain(c)">取消</button>
            </td>
          </tr>
          <tr v-if="!chains.length"><td colspan="8" style="color:var(--muted)">暂无任务链</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="panel modal" style="width:820px">
        <h3>任务链 {{ detail.chain.chain_no }} · 段明细</h3>
        <p class="muted" style="margin:0 0 10px">
          状态 <span :class="['tag', statusClass(detail.chain.status)]">{{ detail.chain.status }}</span>
          · 策略 {{ detail.chain.select_strategy }} · 优先级 {{ detail.chain.priority }}
        </p>
        <table>
          <thead><tr><th>序</th><th>物化任务号</th><th>设备</th><th>类型</th><th>状态</th><th>故障码</th><th>重试</th></tr></thead>
          <tbody>
            <tr v-for="t in detail.tasks" :key="t.id">
              <td>{{ t.seq_no }}</td>
              <td>{{ t.task_no }}</td>
              <td>{{ appName(t.app_id) }}</td>
              <td>{{ t.payload?.cmd_TaskType ?? t.task_type ?? '-' }}<br /><button @click="showTask(t)">点位与结果</button></td>
              <td><span :class="['tag', statusClass(t.status)]">{{ t.status }}</span></td>
              <td><code v-if="t.error_code">{{ t.error_code }}</code><span v-else>-</span></td>
              <td>{{ t.retry_count ?? 0 }}</td>
            </tr>
            <tr v-if="!detail.tasks.length"><td colspan="7" style="color:var(--muted)">尚未物化任何子任务</td></tr>
          </tbody>
        </table>
        <div class="toolbar" style="margin-top:10px"><span class="spacer"></span><button @click="detail = null">关闭</button></div>
      </div>
    </div>
    <div v-if="taskInfo" class="modal-mask task-info-mask" @click.self="taskInfo = null"><div class="panel modal task-info">
      <h3>任务 {{ taskInfo.task_no }}</h3><div class="toolbar"><span :class="['tag', statusClass(taskInfo.status)]">{{ taskInfo.status }}</span><span class="muted">{{ appName(taskInfo.app_id) }} / 协议发布版本 #{{ taskInfo.protocol_revision_id || '-' }}</span><span class="muted">设备任务号 {{ taskInfo.native_task_num ?? '-' }}</span></div>
      <div class="grid2"><div><h4>请求点位 payload</h4><pre>{{ pretty(taskInfo.payload) }}</pre></div><div><h4>本任务采集结果 result</h4><p v-if="!taskInfo.result" class="muted">尚未采集到本任务的有效结果</p><pre v-else>{{ pretty(taskInfo.result) }}</pre></div></div>
      <div class="toolbar"><span class="spacer"></span><button @click="taskInfo = null">关闭</button></div>
    </div></div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 4px; margin-left: 14px; }
.muted { color: var(--muted); }
.task-info { width: min(980px, 94vw); }
.task-info-mask { z-index: 60; }
h4 { font-size: 13px; margin: 8px 0; }
pre { padding: 10px; background: var(--bg); max-height: 50vh; overflow: auto; font-size: 12px; }
</style>
