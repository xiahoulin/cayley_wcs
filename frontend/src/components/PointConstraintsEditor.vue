<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { parsePointObject, parsePointJson } from '../utils/pointJson'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; validity: [valid: boolean] }>()
const error = ref('')
const fields = ref<Record<string, string>>({})
let lastEmitted = ''
const keys = [
  { key: 'min', label: '最小值' }, { key: 'max', label: '最大值' },
  { key: 'max_length', label: '字符串最大长度' }, { key: 'length', label: '数组固定长度' },
  { key: 'element_type', label: '数组元素类型' }, { key: 'encoding', label: '字符编码' },
  { key: 'unit', label: '单位' }, { key: 'wire_encoding', label: '传输编码' },
  { key: 'enum', label: '完整枚举（JSON 数组）' }, { key: 'dimensions', label: '数组维度（JSON 数组）' },
]
const jsonKeys = new Set(['enum', 'dimensions'])
const integerKeys = new Set(['max_length', 'length'])
const extras = computed(() => {
  try { return Object.keys(parsePointObject(props.modelValue)).filter(k => !keys.some(f => f.key === k)).join('、') }
  catch { return '' }
})
watch(() => props.modelValue, text => {
  try {
    const values = parsePointObject(text)
    if (text !== lastEmitted) fields.value = Object.fromEntries(keys.map(f => [f.key, values[f.key] === undefined ? '' : jsonKeys.has(f.key) ? JSON.stringify(values[f.key]) : String(values[f.key])]))
    error.value = ''
    emit('validity', true)
  } catch (e: any) { error.value = e.message; emit('validity', false) }
}, { immediate: true })
function update(key: string) {
  try {
    const values = parsePointObject(props.modelValue)
    const input = fields.value[key]?.trim() || ''
    if (!input) delete values[key]
    else if (jsonKeys.has(key)) {
      const parsed = parsePointJson(input)
      if (!Array.isArray(parsed)) throw new Error('枚举和维度必须是 JSON 数组')
      values[key] = parsed
    } else if (integerKeys.has(key)) {
      const num = Number(input)
      if (!Number.isSafeInteger(num) || num < 0) throw new Error('长度必须是非负整数')
      values[key] = num
    } else values[key] = input
    lastEmitted = JSON.stringify(values, null, 2)
    emit('update:modelValue', lastEmitted)
    error.value = ''
    emit('validity', true)
  } catch (e: any) { error.value = e.message; emit('validity', false) }
}
</script>

<template>
  <div class="constraint-fields">
    <div v-for="f in keys" :key="f.key" class="field"><label>{{ f.label }}</label><input v-model="fields[f.key]" @change="update(f.key)" :placeholder="f.key === 'wire_encoding' ? 'decimal_string' : ''" /></div>
  </div>
  <p v-if="error" class="tag err">{{ error }}</p>
  <details><summary>完整约束 JSON{{ extras ? `（还有 ${extras}）` : '' }}</summary><textarea :value="modelValue" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" rows="4" /></details>
</template>

<style scoped>
.constraint-fields { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }
.field { margin-bottom: 4px; }
details { color: var(--muted); font-size: 12px; margin: 6px 0; }
textarea { font-family: Consolas, monospace; margin-top: 4px; }
@media (max-width: 720px) { .constraint-fields { grid-template-columns: 1fr 1fr; } }
</style>
