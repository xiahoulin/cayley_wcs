<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { bindingGranted, bindingGrant, applicationAll, type Application } from '../api/wcs'

const apps = ref<Application[]>([])
const error = ref('')
const ok = ref('')
const selectedUpstream = ref<number | null>(null)
const checked = ref<Record<number, boolean>>({})
const saving = ref(false)

// 不锁 direction：任意应用都可作上位侧/下位侧；下位侧列表排除上位侧自身（不允许自绑定）。
const downstreams = computed(() => apps.value.filter((a) => a.id !== selectedUpstream.value))

function appLabel(id: number) {
  const a = apps.value.find((x) => x.id === id)
  return a ? `${a.app_name}（${a.app_code}）` : id
}

async function load() {
  error.value = ''
  try {
    apps.value = await applicationAll()
    const stillExists = apps.value.some((a) => a.id === selectedUpstream.value)
    if (!stillExists) selectedUpstream.value = apps.value[0]?.id ?? null
    await loadGranted()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  }
}

async function loadGranted() {
  checked.value = {}
  if (selectedUpstream.value == null) return
  const granted = await bindingGranted(selectedUpstream.value)
  const map: Record<number, boolean> = {}
  for (const id of granted) map[id] = true
  checked.value = map
}

async function onUpstreamChange() {
  ok.value = ''
  await loadGranted()
}

function toggle(id: number) {
  checked.value = { ...checked.value, [id]: !checked.value[id] }
}

async function saveGrant() {
  if (selectedUpstream.value == null) return
  saving.value = true
  error.value = ''
  ok.value = ''
  try {
    const ids = downstreams.value.map((d) => d.id!).filter((id) => checked.value[id])
    await bindingGrant(selectedUpstream.value, ids)
    ok.value = `已保存：${appLabel(selectedUpstream.value)} 可指挥 ${ids.length} 个下位侧应用`
    await loadGranted()
  } catch (e: any) {
    error.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 style="margin:0">授权绑定</h2>
      <span class="spacer"></span>
      <button @click="load">刷新</button>
      <button class="primary" :disabled="selectedUpstream == null || saving" @click="saveGrant">
        {{ saving ? '保存中…' : '保存授权' }}
      </button>
    </div>
    <p class="hint">为<strong>上位侧应用</strong>勾选它可指挥的<strong>下位侧应用</strong>。不按 direction 锁方向：任意应用都可作上位侧或下位侧（含「下位→下位」），但不能指挥自己。<code>/open/task/dispatch</code> 仅当上位侧(验签身份)与下位侧应用存在启用中的授权时放行，否则拒绝（防越权）。明细查看与单条增删见「绑定明细」。</p>
    <p v-if="error" class="tag err">{{ error }}</p>
    <p v-if="ok" class="tag ok">{{ ok }}</p>

    <div class="panel grant">
      <div class="grant-head">
        <label>上位侧应用</label>
        <select v-model.number="selectedUpstream" @change="onUpstreamChange" style="min-width:300px">
          <option v-if="!apps.length" :value="null">（无应用，请先在「应用管理」新建）</option>
          <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}（{{ a.app_code }} · {{ a.direction }} · {{ a.app_key }}）</option>
        </select>
      </div>
      <p class="sub">勾选 = 授权指挥；取消勾选并保存 = 撤销授权。下位侧列表已排除上位侧自身（不允许自绑定）。每个应用括号内标注其 direction（上位/下位）仅作提示，不限制可否被选。</p>
      <div class="dev-grid">
        <label v-for="d in downstreams" :key="d.id" class="dev" :class="{ on: checked[d.id!] }">
          <input type="checkbox" :checked="checked[d.id!]" @change="toggle(d.id!)" :disabled="selectedUpstream == null" />
          <span class="dev-name">{{ d.app_name }}</span>
          <span class="dev-code">{{ d.app_code }} · {{ d.direction }}</span>
        </label>
        <p v-if="!downstreams.length" style="color:var(--muted)">无可授权的下位侧应用</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { color: var(--muted); font-size: 13px; margin: 4px 0 12px; }
.sub { color: var(--muted); font-size: 12px; margin: 6px 0 10px; }
.grant-head { display: flex; align-items: center; gap: 10px; }
.grant-head label { font-weight: 600; }
.dev-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px; }
.dev { display: flex; align-items: center; gap: 8px; padding: 9px 11px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; }
.dev.on { border-color: var(--primary); background: rgba(46,116,181,0.08); }
.dev-name { font-weight: 600; }
.dev-code { color: var(--muted); font-size: 12px; }
</style>
