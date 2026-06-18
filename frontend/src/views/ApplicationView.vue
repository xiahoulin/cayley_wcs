<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  applicationList, applicationCreate, applicationUpdate, applicationDelete, applicationResetSecret,
  protocolAll, type Application, type Protocol,
} from '../api/wcs'

const rows = ref<Application[]>([])
const protocols = ref<Protocol[]>([])
const error = ref('')
const editing = ref<Application | null>(null)
const connText = ref('{}')

function protocolName(id: number) {
  return protocols.value.find((p) => p.id === id)?.protocol_name || id
}

async function load() {
  error.value = ''
  try {
    rows.value = (await applicationList()).rows
    protocols.value = await protocolAll()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  }
}

function openNew() {
  editing.value = {
    app_code: '', app_name: '', protocol_id: protocols.value[0]?.id || 0,
    direction: 'downstream', heartbeat_interval_ms: 5000, enabled: true,
  }
  connText.value = '{}'
}
function openEdit(a: Application) {
  editing.value = { ...a }
  connText.value = JSON.stringify(a.conn_params ?? {}, null, 2)
}
async function save() {
  if (!editing.value) return
  try {
    editing.value.conn_params = JSON.parse(connText.value || '{}')
  } catch {
    error.value = 'conn_params 不是合法 JSON'
    return
  }
  try {
    if (editing.value.id) await applicationUpdate(editing.value)
    else await applicationCreate(editing.value)
    editing.value = null
    await load()
  } catch (e: any) {
    error.value = e?.message || '保存失败'
  }
}
async function resetSecret(a: Application) {
  if (!a.id || !confirm('重置 AppSecret？旧密钥立即失效。')) return
  const updated = await applicationResetSecret(a.id)
  alert('新 AppSecret：\n' + updated.app_secret)
  await load()
}
async function remove(a: Application) {
  if (!a.id || !confirm(`删除应用「${a.app_name}」？`)) return
  await applicationDelete(a.id)
  await load()
}

onMounted(load)
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
          <tr><th>编码</th><th>名称</th><th>协议</th><th>方向</th><th>AppKey</th><th>启用</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in rows" :key="a.id">
            <td>{{ a.app_code }}</td>
            <td>{{ a.app_name }}</td>
            <td>{{ protocolName(a.protocol_id) }}</td>
            <td>{{ a.direction }}</td>
            <td><code>{{ a.app_key }}</code></td>
            <td><span :class="['tag', a.enabled ? 'ok' : 'muted']">{{ a.enabled ? '启用' : '停用' }}</span></td>
            <td>
              <button @click="openEdit(a)">编辑</button>
              <button @click="resetSecret(a)">重置密钥</button>
              <button class="danger" @click="remove(a)">删除</button>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="7" style="color:var(--muted)">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="panel modal">
        <h3>{{ editing.id ? '编辑应用' : '新建应用' }}</h3>
        <div class="grid2">
          <div class="field"><label>应用编码</label><input v-model="editing.app_code" /></div>
          <div class="field"><label>应用名称</label><input v-model="editing.app_name" /></div>
          <div class="field">
            <label>绑定协议</label>
            <select v-model="editing.protocol_id">
              <option v-for="p in protocols" :key="p.id" :value="p.id">{{ p.protocol_name }}</option>
            </select>
          </div>
          <div class="field">
            <label>方向</label>
            <select v-model="editing.direction">
              <option value="downstream">下位（设备）</option>
              <option value="upstream">上位（WMS）</option>
            </select>
          </div>
          <div class="field"><label>心跳间隔(ms)</label><input v-model.number="editing.heartbeat_interval_ms" type="number" /></div>
          <div class="field">
            <label>启用</label>
            <select v-model="editing.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </div>
        </div>
        <div v-if="editing.app_key" class="field">
          <label>AppKey / AppSecret</label>
          <input :value="editing.app_key + '  /  ' + (editing.app_secret || '******')" readonly />
        </div>
        <div class="field"><label>连接参数 conn_params (JSON)</label>
          <textarea v-model="connText" rows="5" style="font-family:monospace"></textarea>
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
