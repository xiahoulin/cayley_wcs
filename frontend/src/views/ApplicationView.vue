<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  applicationList, applicationCreate, applicationUpdate, applicationDelete, applicationResetSecret,
  protocolAll, protocolRevisions, dictItems, type Application, type Protocol, type ProtocolRevision, type DictItem,
  applicationAdapterOptions, type ApplicationAdapterOption,
} from '../api/wcs'

const rows = ref<Application[]>([])
const router = useRouter()
const route = useRoute()
const protocols = ref<Protocol[]>([])
const deviceTypes = ref<DictItem[]>([])
const adapterOptions = ref<ApplicationAdapterOption[]>([])
const error = ref('')
const editing = ref<Application | null>(null)
const connText = ref('{}')
const seerIp = ref('')
const revisions = ref<ProtocolRevision[]>([])
const saving = ref(false)
const ownedProtocols = computed(() => protocols.value.filter(p => p.app_id === editing.value?.id))
const selectedRevision = computed(() => revisions.value.find(r => r.id === editing.value?.protocol_revision_id))
const templateSummary = computed(() => [...new Set(selectedRevision.value?.points?.map(p => `${p.template_code} v${p.template_version}`) || [])].join('、'))
const deviceTemplate = computed(() => editing.value?.device_type === 'palletizer' ? 'stacker' : editing.value?.device_type)

function deviceTypeName(code?: string) {
  if (!code) return '-'
  return deviceTypes.value.find((d) => d.item_value === code)?.item_name || code
}

function protocolName(id?: number | null) {
  return protocols.value.find((p) => p.id === id)?.protocol_name || id || '待配置'
}

async function load() {
  error.value = ''
  try {
    rows.value = (await applicationList()).rows
    protocols.value = await protocolAll()
    deviceTypes.value = await dictItems('device_type')
    adapterOptions.value = await applicationAdapterOptions()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  }
}

function openNew() {
  editing.value = {
    app_code: '', app_name: '',
    direction: 'downstream', device_type: 'stacker', adapter_type: 'standard', heartbeat_interval_ms: 5000, enabled: true,
  }
  connText.value = '{}'
  seerIp.value = ''
  loadRevisions()
}
function openEdit(a: Application) {
  editing.value = { ...a, adapter_type: a.adapter_type || 'standard' }
  connText.value = JSON.stringify(a.conn_params ?? {}, null, 2)
  seerIp.value = String((a.conn_params as Record<string, unknown> | undefined)?.ip ?? '')
  loadRevisions()
}
async function loadRevisions() {
  const id = editing.value?.protocol_id
  revisions.value = []
  if (!id) return
  try {
    const list = await protocolRevisions(id)
    if (editing.value?.protocol_id === id) revisions.value = list
  } catch (e: any) { error.value = e?.message || '版本加载失败' }
}
function changeProtocol() {
  if (!editing.value) return
  editing.value.protocol_revision_id = protocols.value.find(p => p.id === editing.value?.protocol_id)?.published_revision_id
  loadRevisions()
}
async function save() {
  if (!editing.value) return
  try {
    editing.value.conn_params = JSON.parse(connText.value || '{}')
    if (!editing.value.conn_params || typeof editing.value.conn_params !== 'object' || Array.isArray(editing.value.conn_params)) { error.value = '连接参数必须为 JSON 对象'; return }
    if (editing.value.adapter_type === 'seer_agv') {
      const ip = seerIp.value.trim()
      if (!/^(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3}$/.test(ip) || ip.split('.').some(n => Number(n) > 255) || Number(ip.split('.')[0]) < 1 || Number(ip.split('.')[0]) >= 224) {
        error.value = '请输入有效的 AGV IPv4 地址'; return
      }
      editing.value.conn_params = { ...(editing.value.conn_params as Record<string, unknown>), ip }
    }
  } catch {
    error.value = 'conn_params 不是合法 JSON'
    return
  }
  try {
    saving.value = true
    if (editing.value.id) await applicationUpdate(editing.value)
    else {
      const created = await applicationCreate(editing.value)
      editing.value = null
      await router.push({ path: '/protocol', query: { appId: created.id } })
      return
    }
    editing.value = null
    await load()
  } catch (e: any) {
    error.value = e?.message || '保存失败'
  } finally { saving.value = false }
}
async function resetSecret(a: Application) {
  if (!a.id || !confirm('重置 AppSecret？旧密钥立即失效。')) return
  const updated = await applicationResetSecret(a.id)
  alert('新 AppSecret：\n' + updated.app_secret)
  await load()
}
async function remove(a: Application) {
  if (!a.id || !confirm(`永久删除应用「${a.app_name}」及其所属协议、点位、发布版本和关联缓存？存在任务或授权引用时将禁止删除，请先关闭连接。`)) return
  try { await applicationDelete(a.id); await load() }
  catch (e: any) { error.value = e?.message || '删除失败' }
}

onMounted(async () => { await load(); const requested = rows.value.find(a => a.id === Number(route.query.appId)); if (requested) openEdit(requested) })
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">应用管理</h2>
      <span class="spacer"></span>
      <button @click="load">刷新</button>
      <button class="primary" @click="openNew">+ 新建应用</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>AppID / 编码</th><th>名称</th><th>当前启用协议</th><th>方向</th><th>设备类型</th><th>AppKey</th><th>启用</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in rows" :key="a.id">
            <td><strong>{{ a.id }}</strong><br />{{ a.app_code }}</td>
            <td>{{ a.app_name }}</td>
            <td>{{ protocolName(a.protocol_id) }}<br /><small style="color:var(--muted)">发布版本 #{{ a.protocol_revision_id || '-' }} · {{ adapterOptions.find(o => o.code === (a.adapter_type || 'standard'))?.name || a.adapter_type }}</small></td>
            <td>{{ a.direction }}</td>
            <td><span class="tag muted">{{ deviceTypeName(a.device_type) }}</span></td>
            <td><code>{{ a.app_key }}</code></td>
            <td><span :class="['tag', a.enabled ? 'ok' : 'muted']">{{ a.enabled ? '启用' : '停用' }}</span></td>
            <td>
              <button @click="openEdit(a)">编辑</button>
              <button @click="router.push({ path: '/protocol', query: { appId: a.id } })">所属协议与点位</button>
              <button @click="resetSecret(a)">重置密钥</button>
              <button class="danger" @click="remove(a)">删除</button>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="8" style="color:var(--muted)">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑应用' : '新建应用' }}</h3>
        <p class="contract-hint">{{ editing.id ? `WCS AppID：${editing.id}，由平台分配，不可修改。` : '先注册应用，由 WCS 分配 AppID 和密钥，再维护该应用的协议与点位。' }}</p>
        <p v-if="error" class="tag err">{{ error }}</p>
        <div class="grid2">
          <div class="field"><label>应用编码</label><input v-model="editing.app_code" /></div>
          <div class="field"><label>应用名称</label><input v-model="editing.app_name" /></div>
          <div v-if="editing.id" class="field">
            <label>启用本应用协议</label>
            <select v-model="editing.protocol_id" @change="changeProtocol">
              <option v-if="!editing.protocol_id" :value="editing.protocol_id">待配置</option>
              <option v-for="p in ownedProtocols" :key="p.id" :value="p.id" :disabled="p.lifecycle_state !== 'published' && p.id !== editing.protocol_id">{{ p.protocol_name }}{{ p.lifecycle_state === 'published' ? '' : '（未发布）' }}</option>
            </select>
          </div>
          <div class="field">
            <label>方向</label>
            <select v-model="editing.direction">
              <option value="downstream">下位（设备）</option>
              <option value="upstream">上位（对接应用）</option>
            </select>
          </div>
          <div v-if="editing.id" class="field">
            <label>固定发布版本</label><select v-model="editing.protocol_revision_id">
              <option v-if="!revisions.length" :value="undefined">暂无发布版本</option>
              <option v-for="r in revisions" :key="r.id" :value="r.id">版本 #{{ r.id }}{{ r.version ? ` / ${r.version}` : '' }}</option>
            </select>
          </div>
          <div class="field">
            <label>设备类型</label>
            <select v-model="editing.device_type">
              <option v-for="d in deviceTypes" :key="d.item_value" :value="d.item_value">{{ d.item_name }}</option>
            </select>
          </div>
          <div class="field"><label>心跳间隔(ms)</label><input v-model.number="editing.heartbeat_interval_ms" type="number" /></div>
          <div class="field"><label>应用适配器</label><select v-model="editing.adapter_type"><option v-for="option in adapterOptions" :key="option.code" :value="option.code">{{ option.name }}</option></select><small style="color:var(--muted)">负责应用报文编解码或共享采集，须匹配协议和设备类型。</small></div>
          <div class="field">
            <label>启用</label>
            <select v-model="editing.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </div>
        </div>
        <div v-if="editing.id" class="contract-hint"><strong>设备点位模板</strong><p>{{ editing.direction === 'upstream' ? (templateSummary || '所选发布版本尚无模板') : `${deviceTypeName(editing.device_type)} 使用 ${deviceTemplate || '-'} 模板` }}</p><small>仅可选用本应用的已发布协议。切换前须结束相关设备作业，后台同步校验现有授权绑定。</small></div>
        <div v-if="editing.app_key" class="field">
          <label>AppKey / AppSecret</label>
          <input :value="editing.app_key + '  /  ' + (editing.app_secret || '******')" readonly />
        </div>
        <div v-if="editing.adapter_type === 'seer_agv'" class="field">
          <label for="seer-ip">AGV IP 地址（IPv4）</label>
          <input id="seer-ip" v-model="seerIp" inputmode="decimal" autocomplete="off" placeholder="请输入车辆 IP 地址" />
          <small style="color:var(--muted)">状态采集与站点导航共用此地址。保存后按原连接状态重新连接。</small>
        </div>
        <div class="field"><label>{{ editing.adapter_type === 'seer_agv' ? '高级连接参数（IP 以上方输入为准）' : '连接参数 conn_params (JSON)' }}</label>
          <textarea v-model="connText" rows="5" style="font-family:monospace"></textarea>
        </div>
        <div class="field"><label>备注</label><input v-model="editing.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editing = null">取消</button>
          <button class="primary" :disabled="saving" @click="save">{{ editing.id ? '保存' : '注册并配置协议' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.contract-hint { background: var(--bg); padding: 9px 11px; margin-bottom: 10px; border-radius: 4px; font-size: 12px; }
.contract-hint p { margin: 4px 0; }
.contract-hint small { color: var(--muted); line-height: 1.6; }
</style>
