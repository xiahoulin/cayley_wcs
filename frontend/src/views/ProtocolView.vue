<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  protocolList, protocolCreate, protocolUpdate, protocolDelete, protocolPoints, dictItems,
  type Protocol, type ProtocolPoint, type DictItem,
} from '../api/wcs'

const rows = ref<Protocol[]>([])
const types = ref<DictItem[]>([])
const loading = ref(false)
const error = ref('')

const editing = ref<Protocol | null>(null)
const points = ref<ProtocolPoint[] | null>(null)
const pointsTitle = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await protocolList()).rows
    types.value = await dictItems('protocol_type')
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function openNew() {
  editing.value = { protocol_code: '', protocol_name: '', target_system: '', protocol_type: 'opcua' }
}
function openEdit(p: Protocol) {
  editing.value = { ...p }
}
async function save() {
  if (!editing.value) return
  try {
    if (editing.value.id) await protocolUpdate(editing.value)
    else await protocolCreate(editing.value)
    editing.value = null
    await load()
  } catch (e: any) {
    error.value = e?.message || '保存失败'
  }
}
async function remove(p: Protocol) {
  if (!p.id || !confirm(`删除协议「${p.protocol_name}」？`)) return
  await protocolDelete(p.id)
  await load()
}
async function showPoints(p: Protocol) {
  if (!p.id) return
  pointsTitle.value = `${p.protocol_name} · 点位`
  points.value = await protocolPoints(p.id)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">协议管理</h2>
      <span class="spacer"></span>
      <button @click="load">刷新</button>
      <button class="primary" @click="openNew">+ 新建协议</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>编码</th><th>名称</th><th>对接系统</th><th>类型</th><th>版本</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in rows" :key="p.id">
            <td>{{ p.protocol_code }}</td>
            <td>{{ p.protocol_name }}</td>
            <td>{{ p.target_system }}</td>
            <td><span class="tag muted">{{ p.protocol_type }}</span></td>
            <td>{{ p.version }}</td>
            <td><span class="tag ok">{{ p.status }}</span></td>
            <td>
              <button @click="showPoints(p)">点位</button>
              <button @click="openEdit(p)">编辑</button>
              <button class="danger" @click="remove(p)">删除</button>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="7" style="color:var(--muted)">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑协议' : '新建协议' }}</h3>
        <div class="grid2">
          <div class="field"><label>协议编码</label><input v-model="editing.protocol_code" /></div>
          <div class="field"><label>协议名称</label><input v-model="editing.protocol_name" /></div>
          <div class="field"><label>对接系统</label><input v-model="editing.target_system" /></div>
          <div class="field">
            <label>协议类型</label>
            <select v-model="editing.protocol_type">
              <option v-for="t in types" :key="t.item_value" :value="t.item_value">{{ t.item_name }}</option>
            </select>
          </div>
          <div class="field"><label>版本</label><input v-model="editing.version" /></div>
          <div class="field"><label>状态</label><input v-model="editing.status" placeholder="enabled" /></div>
        </div>
        <div class="field"><label>备注</label><input v-model="editing.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editing = null">取消</button>
          <button class="primary" @click="save">保存</button>
        </div>
      </div>
    </div>

    <!-- 点位弹窗 -->
    <div v-if="points" class="modal-mask" @click.self="points = null">
      <div class="panel modal" style="width:760px">
        <h3>{{ pointsTitle }}</h3>
        <table>
          <thead>
            <tr><th>分组</th><th>字段</th><th>地址</th><th>类型</th><th>读写</th><th>范围</th></tr>
          </thead>
          <tbody>
            <tr v-for="pt in points" :key="pt.id">
              <td>{{ pt.point_group }}</td>
              <td>{{ pt.field_name }}<br /><small style="color:var(--muted)">{{ pt.symbol_name }}</small></td>
              <td>{{ pt.address }}</td>
              <td>{{ pt.data_type }}</td>
              <td>{{ pt.rw }}</td>
              <td>{{ pt.value_range }}</td>
            </tr>
          </tbody>
        </table>
        <div class="toolbar" style="margin-top:10px"><span class="spacer"></span><button @click="points = null">关闭</button></div>
      </div>
    </div>
  </div>
</template>
