<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  protocolAll, faultCodeList, faultCodeCreate, faultCodeUpdate, faultCodeDelete,
  type Protocol, type FaultCode,
} from '../api/wcs'

const protocols = ref<Protocol[]>([])
const protocolId = ref<number | null>(null)
const rows = ref<FaultCode[]>([])
const error = ref('')
const editing = ref<FaultCode | null>(null)

function levelClass(l: string) {
  return l === 'warn' ? 'warn' : l === 'info' ? 'muted' : 'err'
}

async function loadProtocols() {
  error.value = ''
  try {
    protocols.value = await protocolAll()
    if (!protocolId.value && protocols.value.length) protocolId.value = protocols.value[0].id ?? null
  } catch (e: any) { error.value = e?.message || '加载协议失败' }
}
async function loadCodes() {
  if (!protocolId.value) { rows.value = []; return }
  try { rows.value = await faultCodeList(protocolId.value) } catch (e: any) { error.value = e?.message || '加载故障码失败' }
}

function openNew() {
  editing.value = { protocol_id: protocolId.value || 0, code: 0, level: 'error', name: '', message: '', suggestion: '' }
}
function openEdit(f: FaultCode) { editing.value = { ...f } }
async function save() {
  if (!editing.value) return
  try {
    if (editing.value.id) await faultCodeUpdate(editing.value)
    else await faultCodeCreate(editing.value)
    editing.value = null
    await loadCodes()
  } catch (e: any) { error.value = e?.message || '保存失败' }
}
async function remove(f: FaultCode) {
  if (!f.id || !confirm(`删除故障码 ${f.code}「${f.name}」？`)) return
  try { await faultCodeDelete(f.id); await loadCodes() } catch (e: any) { error.value = e?.message || '删除失败' }
}

watch(protocolId, loadCodes)
onMounted(async () => { await loadProtocols(); await loadCodes() })
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">故障码维护</h2>
      <select v-model.number="protocolId" style="width:220px;margin-left:12px">
        <option v-for="p in protocols" :key="p.id" :value="p.id">{{ p.protocol_name }}</option>
      </select>
      <span class="spacer"></span>
      <button @click="loadCodes">刷新</button>
      <button class="primary" :disabled="!protocolId" @click="openNew">+ 新建故障码</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>故障码</th><th>级别</th><th>名称</th><th>报警信息</th><th>处理建议</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in rows" :key="f.id">
            <td><code>{{ f.code }}</code></td>
            <td><span :class="['tag', levelClass(f.level)]">{{ f.level }}</span></td>
            <td>{{ f.name }}</td>
            <td style="white-space:normal">{{ f.message }}</td>
            <td style="white-space:normal;color:var(--muted)">{{ f.suggestion }}</td>
            <td>
              <button @click="openEdit(f)">编辑</button>
              <button class="danger" @click="remove(f)">删除</button>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="6" style="color:var(--muted)">该协议暂无故障码</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑故障码' : '新建故障码' }}</h3>
        <div class="grid2">
          <div class="field"><label>故障码（数值）</label><input v-model.number="editing.code" type="number" /></div>
          <div class="field">
            <label>级别</label>
            <select v-model="editing.level">
              <option value="error">error</option>
              <option value="warn">warn</option>
              <option value="info">info</option>
            </select>
          </div>
        </div>
        <div class="field"><label>名称</label><input v-model="editing.name" /></div>
        <div class="field"><label>报警信息</label><input v-model="editing.message" /></div>
        <div class="field"><label>处理建议</label><textarea v-model="editing.suggestion" rows="3"></textarea></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editing = null">取消</button>
          <button class="primary" @click="save">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>
