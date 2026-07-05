<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  webhookList, webhookCreate, webhookUpdate, webhookDelete, webhookDeliveries,
  applicationAll, type Webhook, type WebhookDelivery, type Application,
} from '../api/wcs'

const EVENT_TYPES = [
  'task.completed', 'task.failed', 'task.cancelled',
  'chain.completed', 'chain.failed', 'chain.cancelled',
  'alarm.raised', 'alarm.cleared',
]

const rows = ref<Webhook[]>([])
const apps = ref<Application[]>([])
const error = ref('')
const editing = ref<Webhook | null>(null)
const deliveries = ref<WebhookDelivery[] | null>(null)
const deliveryTitle = ref('')

function appName(id: number) { return apps.value.find((a) => a.id === id)?.app_name || `#${id}` }
function statusClass(s: string) {
  if (s === 'ok') return 'ok'
  if (s === 'failed') return 'err'
  return 'warn' // pending / 重投中
}
function statusLabel(s: string) {
  if (s === 'ok') return '成功'
  if (s === 'failed') return '失败'
  if (s === 'pending') return '待投递'
  return s
}

async function load() {
  error.value = ''
  try {
    apps.value = await applicationAll()
    rows.value = (await webhookList()).rows
  } catch (e: any) { error.value = e?.message || '加载失败' }
}

function openNew() {
  editing.value = { name: '', url: '', secret: '', event_types: [], scope_app_ids: [], enabled: true }
}
function openEdit(w: Webhook) {
  editing.value = { ...w, event_types: [...(w.event_types || [])], scope_app_ids: [...(w.scope_app_ids || [])] }
}
function toggleEvent(t: string) {
  if (!editing.value) return
  const arr = editing.value.event_types
  const i = arr.indexOf(t)
  if (i >= 0) arr.splice(i, 1); else arr.push(t)
}
async function save() {
  if (!editing.value) return
  if (!editing.value.name || !editing.value.url) { error.value = '名称与 URL 必填'; return }
  if (!editing.value.event_types.length) { error.value = '至少勾选一个事件类型'; return }
  try {
    if (editing.value.id) await webhookUpdate(editing.value)
    else await webhookCreate(editing.value)
    editing.value = null
    await load()
  } catch (e: any) { error.value = e?.message || '保存失败' }
}
async function remove(w: Webhook) {
  if (!w.id || !confirm(`删除 Webhook「${w.name}」？`)) return
  try { await webhookDelete(w.id); await load() } catch (e: any) { error.value = e?.message || '删除失败' }
}
async function showDeliveries(w: Webhook) {
  if (!w.id) return
  deliveryTitle.value = `${w.name} · 最近投递`
  try { deliveries.value = await webhookDeliveries(w.id, 50) } catch (e: any) { error.value = e?.message || '加载投递记录失败' }
}
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">Webhook 订阅</h2>
      <span class="spacer"></span>
      <button @click="load">刷新</button>
      <button class="primary" @click="openNew">+ 新建 Webhook</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>名称</th><th>回调 URL</th><th>订阅事件</th><th>作用域</th><th>启用</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="w in rows" :key="w.id">
            <td>{{ w.name }}</td>
            <td><code>{{ w.url }}</code></td>
            <td style="white-space:normal">
              <span v-for="e in w.event_types" :key="e" class="tag muted" style="margin:1px">{{ e }}</span>
            </td>
            <td>
              <span v-if="!w.scope_app_ids?.length" class="tag ok">全部设备</span>
              <span v-else>{{ w.scope_app_ids.map(appName).join('、') }}</span>
            </td>
            <td><span :class="['tag', w.enabled ? 'ok' : 'muted']">{{ w.enabled ? '启用' : '停用' }}</span></td>
            <td>
              <button @click="showDeliveries(w)">投递记录</button>
              <button @click="openEdit(w)">编辑</button>
              <button class="danger" @click="remove(w)">删除</button>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="6" style="color:var(--muted)">暂无 Webhook</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑 Webhook' : '新建 Webhook' }}</h3>
        <div class="field"><label>名称</label><input v-model="editing.name" /></div>
        <div class="field"><label>回调 URL（http/https）</label><input v-model="editing.url" placeholder="https://wms.example.com/wcs/hook" /></div>
        <div class="field"><label>签名密钥 secret</label><input v-model="editing.secret" placeholder="用于 HMAC 验签" /></div>
        <div class="field">
          <label>订阅事件类型</label>
          <div class="checks">
            <label v-for="t in EVENT_TYPES" :key="t" class="chk">
              <input type="checkbox" :checked="editing.event_types.includes(t)" @change="toggleEvent(t)" /> {{ t }}
            </label>
          </div>
        </div>
        <div class="field">
          <label>作用域应用（多选，留空=全部设备）</label>
          <select v-model="editing.scope_app_ids" multiple size="4">
            <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}</option>
          </select>
        </div>
        <div class="field">
          <label>启用</label>
          <select v-model="editing.enabled"><option :value="true">启用</option><option :value="false">停用</option></select>
        </div>
        <div class="field"><label>备注</label><input v-model="editing.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editing = null">取消</button>
          <button class="primary" @click="save">保存</button>
        </div>
      </div>
    </div>

    <div v-if="deliveries" class="modal-mask" @click.self="deliveries = null">
      <div class="panel modal" style="width:860px">
        <h3>{{ deliveryTitle }}</h3>
        <table>
          <thead><tr><th>事件</th><th>业务ID</th><th>状态</th><th>尝试</th><th>下次重投</th><th>最近响应</th></tr></thead>
          <tbody>
            <tr v-for="d in deliveries" :key="d.id">
              <td>{{ d.event_type }}</td>
              <td>{{ d.biz_id }}</td>
              <td><span :class="['tag', statusClass(d.status)]">{{ statusLabel(d.status) }}</span></td>
              <td>{{ d.attempts }}</td>
              <td>{{ d.next_retry_time || '-' }}</td>
              <td style="white-space:normal;max-width:280px;overflow:hidden;text-overflow:ellipsis">{{ d.last_response }}</td>
            </tr>
            <tr v-if="!deliveries.length"><td colspan="6" style="color:var(--muted)">暂无投递记录</td></tr>
          </tbody>
        </table>
        <div class="toolbar" style="margin-top:10px"><span class="spacer"></span><button @click="deliveries = null">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.checks { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 12px; }
.chk { display: flex; align-items: center; gap: 6px; color: var(--text); font-size: 13px; }
.chk input { width: auto; }
select[multiple] { height: auto; }
</style>
