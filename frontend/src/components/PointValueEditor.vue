<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { ApplicationState, StatePoint } from '../api/deviceState'
import { pointTypeDescription } from '../utils/pointTypes'
import { linkedDoorPoint, parseStatePointInput, pointValueLabel, validateStatePointValue } from '../utils/statePoints'

const props = defineProps<{ state: ApplicationState; point: StatePoint; allowed: boolean; reason: string; busy: boolean; error: string; currentValid: boolean }>()
const emit = defineEmits<{ close: []; submit: [value: unknown] }>()
const choices = computed(() => Array.isArray(props.point.constraints?.enum) ? props.point.constraints.enum : null)
const initial = props.currentValid ? props.point.value : null
const input = ref(initial == null ? '' : typeof initial === 'string' ? initial : JSON.stringify(initial))
const choice = ref(initial == null ? '' : String(choices.value?.findIndex(v => JSON.stringify(v) === JSON.stringify(initial)) ?? ''))
const localError = ref(''), form = ref<HTMLFormElement>()
const arrayType = computed(() => props.point.type.startsWith('ARRAY'))
const textType = computed(() => props.point.type === 'STRING')
const exactInteger = computed(() => props.point.constraints?.wire_encoding === 'decimal_string')
const linked = computed(() => linkedDoorPoint(props.state, props.point))
const doorValue = computed(() => choices.value ? choices.value[Number(choice.value)] : Number(input.value))
const ruleHints = computed(() => {
  const c = props.point.constraints ?? {}, hints: string[] = []
  if (c.min != null) hints.push(`最小值 ${c.min}`)
  if (c.max != null) hints.push(`最大值 ${c.max}`)
  if (c.min_length != null) hints.push(`至少 ${c.min_length} 个字符`)
  if (c.max_length != null) hints.push(`最多 ${c.max_length} 个字符`)
  if (c.length != null) hints.push(`固定 ${c.length} 项`)
  if (c.max_items != null) hints.push(`最多 ${c.max_items} 项`)
  if (Array.isArray(c.dimensions)) hints.push(`数组维度 ${c.dimensions.join(' × ')}`)
  return hints.join('；')
})
const optionLabel = (item: unknown) => typeof item === 'boolean' ? item ? '是' : '否' : typeof item === 'object' ? JSON.stringify(item) : String(item)
function submit() {
  if (!props.allowed || props.busy) return
  localError.value = ''
  try {
    if (choices.value && (choice.value === '' || !Object.hasOwn(choices.value, choice.value))) throw new Error('请选择设置值')
    emit('submit', choices.value ? validateStatePointValue(props.point, choices.value[Number(choice.value)]) : parseStatePointInput(props.point, input.value))
  } catch (e) { localError.value = e instanceof Error ? e.message : String(e) }
}
function close() { if (!props.busy) emit('close') }
function trapTab(event: KeyboardEvent) {
  const controls = Array.from(form.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)') ?? [])
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
onMounted(() => form.value?.querySelector<HTMLElement>('select,textarea,input')?.focus())
</script>

<template>
  <div class="modal-mask value-editor-mask" @click.self="close" @keydown.esc.stop.prevent="close" @keydown.tab="trapTab">
    <form ref="form" class="panel modal value-editor" role="dialog" aria-modal="true" :aria-label="'设置' + point.label" @submit.prevent="submit">
      <div class="editor-header"><div><p class="eyebrow">{{ state.appName }}</p><h3>设置{{ point.label }}</h3></div><button type="button" :disabled="busy" aria-label="关闭设置窗口" @click="close">关闭</button></div>
      <div class="current-reading"><span>当前状态</span><strong>{{ currentValid && point.value != null ? pointValueLabel(point) : point.quality === 'NO_READBACK' ? '未提供回读' : '暂无有效数据' }}<small v-if="currentValid && point.value != null && point.unit"> {{ point.unit }}</small></strong></div>
      <div class="field input-field" @input="localError = ''" @change="localError = ''">
        <label for="state-point-value">设置值<template v-if="point.unit">（{{ point.unit }}）</template></label>
        <select v-if="choices" id="state-point-value" v-model="choice" :disabled="busy || !allowed"><option disabled value="">请选择</option><option v-for="(item, index) in choices" :key="index" :value="String(index)">{{ optionLabel(item) }}</option></select>
        <select v-else-if="point.type === 'BOOL'" id="state-point-value" v-model="input" :disabled="busy || !allowed"><option disabled value="">请选择</option><option value="true">是</option><option value="false">否</option></select>
        <textarea v-else-if="arrayType || textType" id="state-point-value" v-model="input" :rows="arrayType ? 5 : 3" :disabled="busy || !allowed" :placeholder="arrayType ? '请输入数组，例如 [1, 2]' : '请输入文字'" :class="{ 'array-input': arrayType }"></textarea>
        <input v-else id="state-point-value" :value="input" :type="exactInteger ? 'text' : 'number'" :inputmode="point.type.includes('INT') ? 'numeric' : 'decimal'" :step="point.type.includes('INT') ? '1' : 'any'" :disabled="busy || !allowed" :placeholder="point.type.includes('INT') ? '请输入整数' : '请输入数值'" @input="input = ($event.target as HTMLInputElement).value" />
        <p class="input-help">{{ pointTypeDescription(point.type) }}<template v-if="ruleHints"> · {{ ruleHints }}</template></p>
      </div>
      <p v-if="linked" class="linked-notice">自动门联动：设置本项时，“{{ linked.label }}”将同时设为相反值，开门和关门指令恰好一个为 1。{{ (choices ? choice !== '' : input !== '') && (doorValue === 0 || doorValue === 1) ? `本次操作：${(point.field === 'cmd_Open') === (doorValue === 1) ? '开门' : '关门'}。` : '' }}</p>
      <p v-if="!allowed" class="editor-error" role="alert">{{ reason || '当前条件不允许设置，请关闭窗口并刷新状态。' }}</p>
      <p v-if="localError || error" class="editor-error" role="alert">{{ localError || error }}</p>
      <p class="feedback-hint">提交后以设备反馈为准，当前状态会自动更新。</p>
      <div class="editor-footer"><button type="button" :disabled="busy" @click="close">取消</button><button class="primary" type="submit" :disabled="busy || !allowed">{{ busy ? '正在提交…' : '确认设置' }}</button></div>
    </form>
  </div>
</template>

<style scoped>
.value-editor-mask { z-index: 60; background: rgba(22, 28, 38, .35); padding: 16px; }
.value-editor { width: 480px; max-width: 100%; padding: 24px; border-radius: 10px; }
.editor-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.eyebrow { margin: 0 0 6px; font-size: 12px; color: var(--muted); }
.editor-header h3 { font-size: 18px; margin: 0; } .editor-header button { font-size: 12px; }
.current-reading { background: #f5f6f8; padding: 14px; border-radius: 6px; margin: 20px 0; display: flex; gap: 12px; justify-content: space-between; align-items: baseline; }
.current-reading > span { color: var(--muted); font-size: 12px; flex-shrink: 0; } .current-reading strong { overflow-wrap: anywhere; max-height: 110px; overflow: auto; font-weight: 500; }
.input-field label { color: var(--text); font-size: 13px; margin-bottom: 8px; } .input-field input, .input-field select, .input-field textarea { padding: 9px 10px; }
.input-help, .feedback-hint { color: var(--muted); font-size: 12px; line-height: 1.6; }
.array-input { font-family: monospace; } textarea { resize: vertical; }
.linked-notice { background: #fff8ed; color: #9c5b06; padding: 10px; border-radius: 4px; line-height: 1.6; font-size: 12px; }
.editor-error { color: var(--err); font-size: 13px; line-height: 1.5; }
.editor-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 22px; }
</style>
