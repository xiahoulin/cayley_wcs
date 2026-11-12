<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PointConstraintsEditor from '../components/PointConstraintsEditor.vue'
import PointTypeSelect from '../components/PointTypeSelect.vue'
import { parsePointObject, parsePointJson } from '../utils/pointJson'
import { logicalPointTypes } from '../utils/pointTypes'
import {
  protocolAll, protocolTemplates, applicationAll, protocolCreate, protocolUpdate, protocolDelete, protocolPoints, protocolDetail, dictItems,
  protocolPointCreate, protocolPointUpdate, protocolPointDelete, protocolClone, protocolPublish,
  protocolRevisions, protocolTypeOptions,
  type Protocol, type ProtocolPoint, type ProtocolRevision, type DictItem, type Application,
} from '../api/wcs'

const rows = ref<Protocol[]>([])
const route = useRoute()
const router = useRouter()
const applications = ref<Application[]>([])
const legacyTemplates = ref<Protocol[]>([])
const appFilter = ref(Number(route.query.appId) || 0)
const copySource = ref<Protocol | null>(null)
const copyAppId = ref(0)
const visibleRows = computed(() => rows.value.filter(p => !appFilter.value || p.app_id === appFilter.value))
const appName = (id?: number) => applications.value.find(a => a.id === id)?.app_name || '待归属历史模板'
watch(() => route.query.appId, id => { appFilter.value = Number(id) || 0 })
const types = ref<DictItem[]>([])
const error = ref('')
const busy = ref(false)
const editing = ref<Protocol | null>(null)
const selected = ref<Protocol | null>(null)
const points = ref<ProtocolPoint[]>([])
const revisions = ref<ProtocolRevision[]>([])
const pointEditing = ref<ProtocolPoint | null>(null)
const pointError = ref('')
const directionFilter = ref('')
const templateFilter = ref('')
const scopeFilter = ref('')
const defaultText = ref('')
const constraintText = ref('{}')
const constraintsValid = ref(true)
const nativeTypes = ref<string[]>([])
const mutable = computed(() => selected.value?.lifecycle_state === 'draft' && !selected.value?.legacy_template)
const templates = computed(() => [...new Set(points.value.map(p => p.template_code).filter(Boolean))])
const filteredPoints = computed(() => points.value.filter(p =>
  (!directionFilter.value || p.direction === directionFilter.value) &&
  (!templateFilter.value || p.template_code === templateFilter.value) &&
  (!scopeFilter.value || p.point_scope === scopeFilter.value)))
const lifecycleName = (p: Protocol) => ({ draft: '草稿', published: '已发布', retired: '已退役' }[p.lifecycle_state || 'draft'])

async function load() {
  error.value = ''
  try {
    const [list, dictionaries, apps, legacy] = await Promise.all([protocolAll(), dictItems('protocol_type'), applicationAll(), protocolTemplates()])
    rows.value = list; types.value = dictionaries; applications.value = apps; legacyTemplates.value = legacy
  } catch (e: any) { error.value = e?.message || '加载失败' }
}
function openNew() {
  error.value = ''
  editing.value = { app_id: appFilter.value || undefined, protocol_code: '', protocol_name: '', target_system: '', protocol_type: 'opcua', version: '1', status: 'enabled' }
}
function openEdit(p: Protocol) { error.value = ''; editing.value = { ...p } }
async function save() {
  if (!editing.value) return
  error.value = ''; busy.value = true
  try {
    if (editing.value.id) await protocolUpdate(editing.value)
    else await protocolCreate(editing.value)
    editing.value = null; await load()
  } catch (e: any) { error.value = e?.message || '保存失败' }
  finally { busy.value = false }
}
async function remove(p: Protocol) {
  if (!p.id || !confirm(`永久删除协议「${p.protocol_name}」及其点位、发布版本和关联缓存？应用正在选用，或存在任务、任务链、授权引用时将禁止删除。`)) return
  try { await protocolDelete(p.id); await load() }
  catch (e: any) { error.value = e?.message || '删除失败' }
}
async function showPoints(p: Protocol) {
  if (!p.id) return
  error.value = ''; pointError.value = ''; selected.value = { ...p }
  directionFilter.value = ''; templateFilter.value = ''; scopeFilter.value = ''
  await reloadPoints()
}
async function reloadPoints() {
  const id = selected.value?.id
  if (!id) return
  try {
    const [list, history, options, current] = await Promise.all([
      protocolPoints(id), protocolRevisions(id), protocolTypeOptions(selected.value!.protocol_type),
      protocolDetail(id),
    ])
    if (selected.value?.id !== id) return
    points.value = list; revisions.value = history; nativeTypes.value = options; selected.value = current
  } catch (e: any) { pointError.value = e?.message || '点位加载失败' }
}
function editPoint(p?: ProtocolPoint) {
  if (!selected.value?.id || (!mutable.value && !p)) return
  pointError.value = ''
  pointEditing.value = p ? JSON.parse(JSON.stringify(p)) : {
    protocol_id: selected.value.id, field_name: '', symbol_name: '',
    address: '', data_type: 'BOOL', value_range: '', sort: points.value.length,
    template_code: templateFilter.value || 'stacker', template_version: 1,
    operation: 'dispatch', direction: 'request', point_scope: 'business',
    logical_type: 'BOOL', required: true, nullable: false, constraints: {},
  }
  defaultText.value = p?.default_value === undefined || p?.default_value === null ? '' : JSON.stringify(p.default_value)
  constraintText.value = JSON.stringify(p?.constraints || {}, null, 2)
}
function changeDirection() {
  if (!pointEditing.value) return
  const response = pointEditing.value.direction === 'response'
  pointEditing.value.operation = pointEditing.value.point_scope === 'internal' ? 'driver' : response ? 'result' : 'dispatch'
}
function changeScope() {
  if (!pointEditing.value) return
  pointEditing.value.operation = pointEditing.value.point_scope === 'internal' ? 'driver' : pointEditing.value.direction === 'response' ? 'result' : 'dispatch'
}
async function savePoint() {
  const point = pointEditing.value
  if (!point) return
  pointError.value = ''
  try {
    if (!constraintsValid.value) throw new Error('请先修正点位约束')
    if (!point.field_name.trim() || !point.template_code?.trim() || !point.operation?.trim()) throw new Error('请填写逻辑字段、设备模板和操作')
    const constraints = parsePointObject(constraintText.value || '{}')
    point.constraints = constraints
    if (point.scale === '') point.scale = undefined
    point.default_value = defaultText.value.trim() ? parsePointJson(defaultText.value) : null
    busy.value = true
    if (point.id) await protocolPointUpdate(point)
    else await protocolPointCreate(point)
    pointEditing.value = null; await reloadPoints()
  } catch (e: any) { pointError.value = e?.message || '点位保存失败' }
  finally { busy.value = false }
}
async function removePoint(p: ProtocolPoint) {
  if (!p.id || !mutable.value || !confirm(`删除点位「${p.field_name}」？`)) return
  try { await protocolPointDelete(p.id, p.row_version ?? 0); await reloadPoints() }
  catch (e: any) { pointError.value = e?.message || '点位删除失败' }
}
function chooseCopy(p: Protocol) {
  copySource.value = p; copyAppId.value = appFilter.value || p.app_id || 0; error.value = ''
}
async function clone(p: Protocol, appId?: number) {
  if (!p.id) return
  busy.value = true
  try { const draft = await protocolClone(p.id, appId); copySource.value = null; appFilter.value = draft.app_id || 0; await load(); await showPoints(draft) }
  catch (e: any) { error.value = e?.message || '复制失败' }
  finally { busy.value = false }
}
async function publish() {
  if (!selected.value?.id || !mutable.value || !confirm('发布当前协议？后台将检查所有点位定义，发布后通过复制草稿修改。')) return
  busy.value = true; pointError.value = ''
  try {
    selected.value = await protocolPublish(selected.value.id, selected.value.row_version ?? 0)
    await load(); await reloadPoints()
  } catch (e: any) { pointError.value = e?.message || '发布失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar"><h2>应用协议与点位</h2><span class="spacer"></span><button @click="load">刷新</button><button class="primary" :disabled="!applications.length" @click="openNew">新建应用协议</button></div>
    <p class="hint">WCS AppID → 所属协议 → 协议点位。每份协议独属于一个应用，应用固定选用其中一个已发布版本。</p>
    <div class="toolbar"><label for="protocol-app-filter">所属应用</label><select id="protocol-app-filter" v-model="appFilter"><option :value="0">全部应用</option><option v-for="a in applications" :key="a.id" :value="a.id">{{ a.app_name }}（AppID {{ a.id }}）</option></select><button v-if="appFilter" @click="router.push({ path: '/application', query: { appId: appFilter } })">应用配置与版本选择</button></div>
    <p v-if="error && !editing" class="tag err">{{ error }}</p>
    <div class="panel table-wrap"><table>
      <thead><tr><th>所属应用 / AppID</th><th>编码 / 名称</th><th>对接系统</th><th>类型</th><th>版本</th><th>发布状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="p in visibleRows" :key="p.id">
        <td>{{ appName(p.app_id) }}<small>AppID {{ p.app_id }}</small></td><td>{{ p.protocol_code }}<small>{{ p.protocol_name }}</small></td><td>{{ p.target_system }}</td><td>{{ p.protocol_type }}</td><td>{{ p.version || '-' }}</td>
        <td><span :class="['tag', p.lifecycle_state === 'published' ? 'ok' : 'muted']">{{ lifecycleName(p) }}</span></td>
        <td class="actions"><button @click="showPoints(p)">点位与版本</button><button v-if="p.lifecycle_state === 'draft'" @click="openEdit(p)">编辑</button><button :disabled="busy" @click="chooseCopy(p)">复制草稿</button><button class="danger" @click="remove(p)">删除</button></td>
      </tr><tr v-if="!visibleRows.length"><td colspan="7" class="muted">该应用暂无协议，请新建协议或从历史模板复制。</td></tr></tbody>
    </table></div>
    <details v-if="legacyTemplates.length" class="legacy-library panel"><summary>待归属历史模板（{{ legacyTemplates.length }}）</summary><p class="hint">保留升级前未被应用引用的配置，只供查阅和复制。复制到指定应用并发布后，才能用于连接或授权。</p><div class="table-wrap"><table><thead><tr><th>模板编码 / 名称</th><th>协议类型</th><th>操作</th></tr></thead><tbody><tr v-for="p in legacyTemplates" :key="p.id"><td>{{ p.protocol_code }}<small>{{ p.protocol_name }}</small></td><td>{{ p.protocol_type }}</td><td class="actions"><button @click="showPoints(p)">查看点位</button><button :disabled="busy" @click="chooseCopy(p)">复制到应用</button></td></tr></tbody></table></div></details>
    <div v-if="copySource" class="modal-mask copy-mask" @click.self="copySource = null"><div class="panel modal"><h3>复制协议草稿</h3><p>{{ copySource.protocol_name }}</p><p v-if="error" class="tag err">{{ error }}</p><div class="field"><label>目标应用</label><select v-model="copyAppId"><option :value="0" disabled>请选择所属应用</option><option v-for="a in applications" :key="a.id" :value="a.id">{{ a.app_name }}（AppID {{ a.id }}）</option></select></div><p class="hint">复制点位和故障码，生成独立草稿。原应用的配置和当前启用版本保持不变。</p><div class="toolbar footer"><span class="spacer"></span><button @click="copySource = null">取消</button><button class="primary" :disabled="busy || !copyAppId" @click="clone(copySource, copyAppId)">复制到应用</button></div></div></div>
    <div v-if="editing" class="modal-mask" @click.self="editing = null"><div class="panel modal">
      <h3>{{ editing.id ? '编辑协议' : '新建协议' }}</h3><p v-if="error" class="tag err">{{ error }}</p>
      <div class="grid2">
        <div class="field"><label>所属应用（AppID）</label><select v-model="editing.app_id" :disabled="!!editing.id"><option :value="undefined" disabled>请先选择应用</option><option v-for="a in applications" :key="a.id" :value="a.id">{{ a.app_name }}（{{ a.id }}）</option></select></div>
        <div class="field"><label>协议编码</label><input v-model="editing.protocol_code" /></div><div class="field"><label>协议名称</label><input v-model="editing.protocol_name" /></div>
        <div class="field"><label>对接系统</label><input v-model="editing.target_system" /></div><div class="field"><label>协议类型</label><select v-model="editing.protocol_type"><option v-for="t in types" :key="t.item_value" :value="t.item_value">{{ t.item_name }}</option></select></div>
        <div class="field"><label>版本说明</label><input v-model="editing.version" /></div><div class="field"><label>启用状态</label><select v-model="editing.status"><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
      </div><div class="field"><label>备注</label><input v-model="editing.remark" /></div>
      <div class="toolbar footer"><span class="spacer"></span><button @click="editing = null">取消</button><button class="primary" :disabled="busy || !editing.app_id" @click="save">保存草稿</button></div>
    </div></div>
    <div v-if="selected" class="modal-mask" @click.self="selected = null"><div class="panel modal point-modal">
      <div class="toolbar"><h3>{{ selected.protocol_name }} 点位与版本</h3><span :class="['tag', mutable ? 'muted' : 'ok']">{{ lifecycleName(selected) }}</span><span class="spacer"></span>
        <button v-if="!mutable" :disabled="busy" @click="chooseCopy(selected)">复制到应用</button><button v-if="mutable" @click="editPoint()">新增点位</button><button v-if="mutable" class="primary" :disabled="busy" @click="publish">检查并发布</button><button v-if="selected.app_id && !mutable" @click="router.push({ path: '/application', query: { appId: selected.app_id } })">选择启用版本</button><button @click="selected = null">关闭</button>
      </div><p class="hint">{{ appName(selected.app_id) }}{{ selected.app_id ? ` · AppID ${selected.app_id}` : '' }} → {{ selected.protocol_name }} → 点位。已发布版本保留供本应用和历史任务使用。</p>
      <p v-if="pointError && !pointEditing" class="tag err">{{ pointError }}</p>
      <div class="toolbar filters">
        <select v-model="templateFilter"><option value="">全部设备模板</option><option v-for="t in templates" :key="t" :value="t">{{ t }}</option></select>
        <select v-model="directionFilter"><option value="">请求与响应</option><option value="request">请求</option><option value="response">响应</option></select>
        <select v-model="scopeFilter"><option value="">全部用途</option><option value="business">对外业务</option><option value="internal">内部驱动</option></select>
        <span class="muted">{{ filteredPoints.length }} 个点位 · {{ revisions.length }} 个发布版本</span>
      </div><div class="table-wrap point-table"><table>
        <thead><tr><th>模板 / 版本</th><th>方向 / 用途</th><th>操作 / 字段</th><th>逻辑 / 原生类型</th><th>原生地址</th><th>必填 / 空值</th><th>操作</th></tr></thead>
        <tbody><tr v-for="p in filteredPoints" :key="p.id"><td>{{ p.template_code }}<small>v{{ p.template_version }}</small></td><td>{{ p.direction === 'response' ? '响应' : '请求' }}<small>{{ p.point_scope === 'internal' ? '内部驱动' : '对外业务' }}</small></td><td><small>{{ p.operation }}</small><code>{{ p.field_name }}</code><small>{{ p.symbol_name }}</small></td><td>{{ p.logical_type }}<small>{{ p.data_type }}</small></td><td class="address">{{ p.address }}</td><td>{{ p.required ? '必填' : '选填' }} / {{ p.nullable ? '可空' : '不可空' }}</td><td class="actions"><button @click="editPoint(p)">{{ mutable ? '编辑' : '查看' }}</button><button v-if="mutable" class="danger" @click="removePoint(p)">删除</button></td></tr><tr v-if="!filteredPoints.length"><td colspan="7" class="muted">暂无点位</td></tr></tbody>
      </table></div>
      <details v-if="revisions.length" class="revision-list"><summary>已发布版本</summary><div v-for="r in revisions" :key="r.id">版本 #{{ r.id }} <code>{{ r.fingerprint }}</code></div></details>
    </div></div>
    <div v-if="pointEditing" class="modal-mask point-editor-mask" @click.self="pointEditing = null"><div class="panel modal point-editor">
      <h3>{{ !mutable ? '查看点位' : pointEditing.id ? '编辑点位' : '新增点位' }}</h3><p v-if="pointError" class="tag err">{{ pointError }}</p>
      <fieldset :disabled="!mutable || busy">
      <div class="grid3">
        <div class="field"><label>设备模板编码</label><input v-model="pointEditing.template_code" list="device-templates" /><datalist id="device-templates"><option v-for="t in ['stacker','conveyor','lift','auto_door','agv','vibration_sensor','noise_sensor','infrared_sensor']" :key="t" :value="t" /></datalist></div>
        <div class="field"><label>模板版本</label><input v-model.number="pointEditing.template_version" type="number" min="1" /></div>
        <div class="field"><label>操作</label><input v-model="pointEditing.operation" list="point-operations" /><datalist id="point-operations"><option value="dispatch" /><option value="result" /><option value="driver" /></datalist></div>
        <div class="field"><label>方向</label><select v-model="pointEditing.direction" @change="changeDirection"><option value="request">请求</option><option value="response">响应</option></select></div>
        <div class="field"><label>用途</label><select v-model="pointEditing.point_scope" @change="changeScope"><option value="business">对外业务</option><option value="internal">内部驱动</option></select></div>
        <div class="field"><label>逻辑字段编码（区分大小写）</label><input v-model="pointEditing.field_name" /></div>
        <div class="field"><label>中文名称</label><input v-model="pointEditing.symbol_name" /></div>
        <div class="field"><label for="point-logical-type">逻辑类型</label><PointTypeSelect v-model="pointEditing.logical_type" :options="logicalPointTypes" input-id="point-logical-type" label="逻辑类型" /></div>
        <div class="field"><label for="point-native-type">协议原生类型</label><PointTypeSelect v-model="pointEditing.data_type" :options="nativeTypes" input-id="point-native-type" label="协议原生类型" allow-custom /></div>
        <div class="field"><label>原生地址</label><input v-model="pointEditing.address" /></div>
        <div class="field"><label>是否必填</label><select v-model="pointEditing.required"><option :value="true">必填</option><option :value="false">选填</option></select></div>
        <div class="field"><label>是否允许空值</label><select v-model="pointEditing.nullable"><option :value="false">不可空</option><option :value="true">可空</option></select></div>
        <div class="field"><label>排序</label><input v-model.number="pointEditing.sort" type="number" /></div>
      </div>
      <div class="grid3"><div class="field"><label>默认值（JSON；不设置请留空）</label><input v-model="defaultText" placeholder='例如 0、false、"文字" 或 [1,2]' /></div><div class="field"><label>范围说明</label><input v-model="pointEditing.value_range" /></div><div class="field"><label>缩放系数（当前支持1）</label><input v-model="pointEditing.scale" placeholder="1" /></div></div>
      <div class="field"><label>点位约束</label><PointConstraintsEditor v-model="constraintText" @validity="constraintsValid = $event" /></div>
      <p class="hint">约束键支持 min、max、enum、max_length、length、dimensions、element_type、encoding、unit 和 wire_encoding。64 位整数的无损传输格式为 decimal_string；发布时后台校验完整约束。</p>
      <div class="field"><label>点位说明</label><input v-model="pointEditing.description" /></div>
      </fieldset>
      <div class="toolbar footer"><span class="spacer"></span><button @click="pointEditing = null">{{ mutable ? '取消' : '关闭' }}</button><button v-if="mutable" class="primary" :disabled="busy || !constraintsValid" @click="savePoint">保存点位</button></div>
    </div></div>
  </div>
</template>

<style scoped>
h2, h3 { margin: 0; }
small { display: block; color: var(--muted); margin-top: 3px; font-size: 12px; }
.muted, .hint { color: var(--muted); }
.hint { font-size: 12px; line-height: 1.6; margin: 8px 0; }
.table-wrap { overflow: auto; }
.actions { display: flex; gap: 5px; }
.legacy-library { margin-top: 16px; }
.copy-mask { z-index: 65; }
.footer { margin: 12px 0 0; }
.point-modal { width: min(1220px, 96vw); }
.point-editor { width: min(940px, 94vw); }
.point-editor-mask { z-index: 60; }
.point-table { max-height: 56vh; }
.filters select { width: 170px; }
.grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.field { margin-bottom: 5px; }
.code-input { font-family: Consolas, monospace; }
fieldset { border: 0; margin: 10px 0 0; padding: 0; min-width: 0; }
.address { white-space: normal; max-width: 220px; overflow-wrap: anywhere; }
.revision-list { font-size: 12px; margin-top: 10px; overflow-wrap: anywhere; }
@media (max-width: 720px) { .grid3, .grid2 { grid-template-columns: 1fr; } }
</style>
