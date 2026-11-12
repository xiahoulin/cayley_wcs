<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { bindingGranted, bindingGrant, bindingCheck, applicationAll, type Application, type BindingMatch } from '../api/wcs'
import BindingMatchDetails from '../components/BindingMatchDetails.vue'

const apps = ref<Application[]>([])
const error = ref('')
const ok = ref('')
const selectedUpstream = ref<number | null>(null)
const checked = ref<Record<number, boolean>>({})
const saving = ref(false)
const checking = ref(false)
const matches = ref<Record<number, BindingMatch>>({})
const detailId = ref<number | null>(null)
let generation = 0

// 候选保留完整列表；可连接能力和点位约定由后台逐项校验。
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
    await loadMatches()
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
  await loadMatches()
}
async function loadMatches() {
  const id = selectedUpstream.value
  const round = ++generation
  matches.value = {}; detailId.value = null
  if (id == null) return
  checking.value = true
  const results = await Promise.all(downstreams.value.map(async a => {
    try { return [a.id!, await bindingCheck(id, a.id!)] as const }
    catch (e: any) { return [a.id!, { compatible: false, differences: [{ field: '', direction: '', reason: e?.message || '匹配检查失败' }] } as BindingMatch] as const }
  }))
  if (round === generation) { matches.value = Object.fromEntries(results); checking.value = false }
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
    const checkedMatches = await Promise.all(ids.map(async id => [id, await bindingCheck(selectedUpstream.value!, id)] as const))
    matches.value = { ...matches.value, ...Object.fromEntries(checkedMatches) }
    const invalid = checkedMatches.find(([,m]) => !m.compatible)
    if (invalid) { detailId.value = invalid[0]; throw new Error('所选应用存在点位或连接能力差异，请先修正后保存') }
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
      <button class="primary" :disabled="selectedUpstream == null || saving || checking" @click="saveGrant">
        {{ saving ? '保存中…' : '保存授权' }}
      </button>
    </div>
    <p class="hint">为上位应用选择可指挥的设备。双方请求与响应的字段、类型和约束均须匹配，且具备对应收发能力。保存时后台整体校验，全部通过才建立关系。</p>
    <p v-if="error" class="tag err">{{ error }}</p>
    <p v-if="ok" class="tag ok">{{ ok }}</p>

    <div class="panel grant">
      <div class="grant-head">
        <label>上位侧应用</label>
        <select v-model.number="selectedUpstream" @change="onUpstreamChange" :disabled="saving" style="min-width:300px">
          <option v-if="!apps.length" :value="null">（无应用，请先在「应用管理」新建）</option>
          <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.app_name }}（{{ a.app_code }}）</option>
        </select>
      </div>
      <p class="sub">勾选后保存授权；取消勾选后保存撤权。{{ checking ? '正在检查候选应用…' : '点击匹配详情可查看差异。' }}</p>
      <div class="dev-grid">
        <div v-for="d in downstreams" :key="d.id" class="dev" :class="{ on: checked[d.id!] }">
          <label><input type="checkbox" :checked="checked[d.id!]" @change="toggle(d.id!)" :disabled="saving || checking || selectedUpstream == null || (!checked[d.id!] && !matches[d.id!]?.compatible)" /><span class="dev-name">{{ d.app_name }}</span></label>
          <span class="dev-code">{{ d.app_code }} / 版本 #{{ d.protocol_revision_id || '-' }}</span>
          <span :class="['tag', matches[d.id!]?.compatible ? 'ok' : 'err']">{{ checking ? '检查中' : matches[d.id!]?.compatible ? '匹配通过' : '不可连接' }}</span>
          <button :disabled="checking" @click="detailId = d.id!">匹配详情</button>
        </div>
        <p v-if="!downstreams.length" style="color:var(--muted)">无可授权的下位侧应用</p>
      </div>
    </div>
    <div v-if="detailId != null" class="modal-mask" @click.self="detailId = null"><div class="panel modal"><h3>{{ appLabel(detailId) }} 匹配详情</h3><BindingMatchDetails :match="matches[detailId] || null" /><div class="toolbar"><span class="spacer"></span><button @click="detailId = null">关闭</button></div></div></div>
  </div>
</template>

<style scoped>
.hint { color: var(--muted); font-size: 13px; margin: 4px 0 12px; }
.sub { color: var(--muted); font-size: 12px; margin: 6px 0 10px; }
.grant-head { display: flex; align-items: center; gap: 10px; }
.grant-head label { font-weight: 600; }
.dev-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px; }
.dev { display: flex; align-items: flex-start; gap: 6px; flex-direction: column; padding: 9px 11px; border: 1px solid var(--border); border-radius: 4px; }
.dev label { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.dev input { width: auto; }
.dev.on { border-color: var(--primary); background: rgba(46,116,181,0.08); }
.dev-name { font-weight: 600; }
.dev-code { color: var(--muted); font-size: 12px; }
</style>
