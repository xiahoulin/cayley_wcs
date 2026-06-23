<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  bindingAll, bindingCreate, bindingUpdate, bindingDelete,
  applicationAll, type AppBinding, type Application,
} from '../api/wcs'

const rows = ref<AppBinding[]>([])
const apps = ref<Application[]>([])
const error = ref('')
const editing = ref<AppBinding | null>(null)
const search = ref('')

function appLabel(id: number) {
  const a = apps.value.find((x) => x.id === id)
  return a ? `${a.app_name}（${a.app_code}）` : id
}
function appMatches(id: number, q: string) {
  const a = apps.value.find((x) => x.id === id)
  if (!a) return false
  return (a.app_name || '').toLowerCase().includes(q) || (a.app_code || '').toLowerCase().includes(q)
}

// 按应用搜索：匹配上位侧或下位侧应用的名称/编码。
const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter((b) => appMatches(b.upstream_app_id, q) || appMatches(b.downstream_app_id, q))
})

async function load() {
  error.value = ''
  try {
    apps.value = await applicationAll()
    rows.value = await bindingAll()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  }
}

function openNew() {
  const up = apps.value[0]?.id || 0
  editing.value = {
    upstream_app_id: up,
    downstream_app_id: apps.value.find((a) => a.id !== up)?.id || 0,
    scope: 'dispatch', enabled: true, remark: '',
  }
}
function openEdit(b: AppBinding) {
  editing.value = { ...b }
}
async function save() {
  if (!editing.value) return
  if (editing.value.upstream_app_id === editing.value.downstream_app_id) {
    error.value = '不允许自绑定：应用不能指挥自己'
    return
  }
  try {
    if (editing.value.id) await bindingUpdate(editing.value)
    else await bindingCreate(editing.value)
    editing.value = null
    await load()
  } catch (e: any) {
    error.value = e?.message || '保存失败'
  }
}
async function remove(b: AppBinding) {
  if (!b.id || !confirm('删除该绑定？删除后该上位侧将无权指挥对应下位侧应用。')) return
  await bindingDelete(b.id)
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">绑定明细</h2>
      <input v-model="search" placeholder="按应用名称/编码搜索（上位侧或下位侧）" style="min-width:280px" />
      <span class="spacer"></span>
      <button @click="load">刷新</button>
      <button class="primary" @click="openNew">+ 手动新建单条</button>
    </div>
    <p class="hint">全部绑定明细（上位侧 → 下位侧），可手动新建/编辑/删除单条；批量按应用授权见「授权绑定」。自绑定被拒。</p>
    <p v-if="error" class="tag err">{{ error }}</p>

    <div class="panel">
      <table>
        <thead>
          <tr><th>上位侧应用</th><th>下位侧应用</th><th>范围</th><th>启用</th><th>备注</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="b in filtered" :key="b.id">
            <td>{{ appLabel(b.upstream_app_id) }}</td>
            <td>{{ appLabel(b.downstream_app_id) }}</td>
            <td><code>{{ b.scope }}</code></td>
            <td><span :class="['tag', b.enabled ? 'ok' : 'muted']">{{ b.enabled ? '启用' : '停用' }}</span></td>
            <td>{{ b.remark }}</td>
            <td>
              <button @click="openEdit(b)">编辑</button>
              <button class="danger" @click="remove(b)">删除</button>
            </td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="6" style="color:var(--muted)">{{ search ? '无匹配的绑定' : '暂无数据' }}</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑绑定' : '新建绑定' }}</h3>
        <div class="grid2">
          <div class="field">
            <label>上位侧应用</label>
            <select v-model="editing.upstream_app_id">
              <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}（{{ a.app_code }} · {{ a.direction }}）</option>
            </select>
          </div>
          <div class="field">
            <label>下位侧应用</label>
            <select v-model="editing.downstream_app_id">
              <option v-for="a in apps.filter((x) => x.id !== editing!.upstream_app_id)" :key="a.id" :value="a.id">{{ a.app_name }}（{{ a.app_code }} · {{ a.direction }}）</option>
            </select>
          </div>
          <div class="field">
            <label>权限范围</label>
            <select v-model="editing.scope">
              <option value="dispatch">dispatch（下发任务）</option>
              <option value="read">read（只读）</option>
              <option value="control">control（控制）</option>
            </select>
          </div>
          <div class="field">
            <label>启用</label>
            <select v-model="editing.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </div>
        </div>
        <div class="field"><label>备注</label><input v-model="editing.remark" /></div>
        <div class="toolbar" style="margin-top:8px">
          <span class="spacer"></span>
          <button @click="editing = null">取消</button>
          <button class="primary" @click="save">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { color: var(--muted); font-size: 13px; margin: 4px 0 12px; }
</style>
