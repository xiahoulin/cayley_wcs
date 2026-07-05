<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  dictTypeList, dictTypeCreate, dictTypeUpdate, dictTypeDelete,
  dictItems, dictItemCreate, dictItemUpdate, dictItemDelete,
  type DictType, type DictItem,
} from '../api/wcs'

const types = ref<DictType[]>([])
const items = ref<DictItem[]>([])
const current = ref<DictType | null>(null)
const error = ref('')

const editingType = ref<DictType | null>(null)
const editingItem = ref<DictItem | null>(null)

async function loadTypes() {
  error.value = ''
  try {
    types.value = await dictTypeList()
    if (!current.value && types.value.length) select(types.value[0])
  } catch (e: any) { error.value = e?.message || '加载失败' }
}
async function select(t: DictType) {
  current.value = t
  try { items.value = await dictItems(t.type_code) } catch (e: any) { error.value = e?.message || '加载明细失败' }
}

function newType() { editingType.value = { type_code: '', type_name: '', sort: 0 } }
function editType(t: DictType) { editingType.value = { ...t } }
async function saveType() {
  if (!editingType.value) return
  try {
    if (editingType.value.id) await dictTypeUpdate(editingType.value)
    else await dictTypeCreate(editingType.value)
    editingType.value = null
    await loadTypes()
  } catch (e: any) { error.value = e?.message || '保存失败' }
}
async function removeType(t: DictType) {
  if (!t.id || !confirm(`删除字典类型「${t.type_name}」及其全部项？`)) return
  try {
    await dictTypeDelete(t.id)
    if (current.value?.id === t.id) { current.value = null; items.value = [] }
    await loadTypes()
  } catch (e: any) { error.value = e?.message || '删除失败' }
}

function newItem() {
  if (!current.value) return
  editingItem.value = { type_code: current.value.type_code, item_code: '', item_name: '', item_value: '', sort: 0 }
}
function editItem(i: DictItem) { editingItem.value = { ...i } }
async function saveItem() {
  if (!editingItem.value) return
  try {
    if (editingItem.value.id) await dictItemUpdate(editingItem.value)
    else await dictItemCreate(editingItem.value)
    editingItem.value = null
    if (current.value) await select(current.value)
  } catch (e: any) { error.value = e?.message || '保存失败' }
}
async function removeItem(i: DictItem) {
  if (!i.id || !confirm(`删除字典项「${i.item_name}」？`)) return
  try {
    await dictItemDelete(i.id)
    if (current.value) await select(current.value)
  } catch (e: any) { error.value = e?.message || '删除失败' }
}

onMounted(loadTypes)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">数据字典</h2>
      <span class="spacer"></span>
      <button @click="loadTypes">刷新</button>
    </div>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="dict-layout">
      <div class="panel">
        <div class="pane-head">
          <strong>字典类型</strong><span class="spacer"></span>
          <button class="primary" @click="newType">+ 类型</button>
        </div>
        <table>
          <thead><tr><th>编码</th><th>名称</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="t in types" :key="t.id" :class="{ selrow: current?.id === t.id }" @click="select(t)"
                style="cursor:pointer">
              <td><code>{{ t.type_code }}</code></td>
              <td>{{ t.type_name }}</td>
              <td>
                <button @click.stop="editType(t)">编辑</button>
                <button class="danger" @click.stop="removeType(t)">删除</button>
              </td>
            </tr>
            <tr v-if="!types.length"><td colspan="3" style="color:var(--muted)">暂无类型</td></tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <div class="pane-head">
          <strong>字典项<span v-if="current" class="muted"> · {{ current.type_name }}</span></strong>
          <span class="spacer"></span>
          <button class="primary" :disabled="!current" @click="newItem">+ 字典项</button>
        </div>
        <table>
          <thead><tr><th>编码</th><th>名称</th><th>值</th><th>排序</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="i in items" :key="i.id">
              <td><code>{{ i.item_code }}</code></td>
              <td>{{ i.item_name }}</td>
              <td>{{ i.item_value }}</td>
              <td>{{ i.sort }}</td>
              <td>
                <button @click="editItem(i)">编辑</button>
                <button class="danger" @click="removeItem(i)">删除</button>
              </td>
            </tr>
            <tr v-if="!items.length"><td colspan="5" style="color:var(--muted)">{{ current ? '该类型暂无字典项' : '请选择左侧类型' }}</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="editingType" class="modal-mask" @click.self="editingType = null">
      <div class="panel modal">
        <h3>{{ editingType.id ? '编辑字典类型' : '新建字典类型' }}</h3>
        <div class="grid2">
          <div class="field"><label>类型编码</label><input v-model="editingType.type_code" :readonly="!!editingType.id" /></div>
          <div class="field"><label>类型名称</label><input v-model="editingType.type_name" /></div>
          <div class="field"><label>排序</label><input v-model.number="editingType.sort" type="number" /></div>
        </div>
        <div class="field"><label>备注</label><input v-model="editingType.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editingType = null">取消</button>
          <button class="primary" @click="saveType">保存</button>
        </div>
      </div>
    </div>

    <div v-if="editingItem" class="modal-mask" @click.self="editingItem = null">
      <div class="panel modal">
        <h3>{{ editingItem.id ? '编辑字典项' : '新建字典项' }}</h3>
        <div class="grid2">
          <div class="field"><label>字典项编码</label><input v-model="editingItem.item_code" /></div>
          <div class="field"><label>字典项名称</label><input v-model="editingItem.item_name" /></div>
          <div class="field"><label>值</label><input v-model="editingItem.item_value" /></div>
          <div class="field"><label>排序</label><input v-model.number="editingItem.sort" type="number" /></div>
        </div>
        <div class="field"><label>备注</label><input v-model="editingItem.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editingItem = null">取消</button>
          <button class="primary" @click="saveItem">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dict-layout { display: grid; grid-template-columns: 1fr 1.4fr; gap: 14px; align-items: start; }
.pane-head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); }
.selrow td { background: var(--selected) !important; }
.muted { color: var(--muted); font-weight: 400; }
</style>
