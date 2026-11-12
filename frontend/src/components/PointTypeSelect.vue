<script setup lang="ts">
import { computed, ref } from 'vue'
import { isArrayPointType, pointTypeDescription } from '../utils/pointTypes'

const props = defineProps<{
  modelValue?: string
  options: string[]
  inputId: string
  label: string
  allowCustom?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const manual = ref(false)
const manualOption = '__manual_type_declaration__'
const basicTypes = computed(() => [...new Set(props.options.filter(type => !isArrayPointType(type)))])
const arrayTypes = computed(() => [...new Set([
  ...props.options.filter(isArrayPointType),
  ...basicTypes.value.map(type => `ARRAY<${type}>`),
])])
const existingCustom = computed(() => !!props.modelValue
  && !basicTypes.value.includes(props.modelValue) && !arrayTypes.value.includes(props.modelValue))
const selection = computed(() => manual.value ? manualOption : props.modelValue || '')

function choose(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (value === manualOption) {
    manual.value = true
    return
  }
  manual.value = false
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="point-type-select">
    <select :id="inputId" :aria-label="label" :value="selection" @change="choose">
      <option v-if="!modelValue" value="" disabled>请选择类型</option>
      <optgroup label="基础类型">
        <option v-for="type in basicTypes" :key="type" :value="type">{{ type }} · {{ pointTypeDescription(type) }}</option>
      </optgroup>
      <optgroup label="数组类型">
        <option v-for="type in arrayTypes" :key="type" :value="type">{{ type }} · {{ pointTypeDescription(type) }}</option>
      </optgroup>
      <optgroup v-if="existingCustom" label="当前类型（保留原值）">
        <option :value="modelValue">{{ modelValue }} · {{ pointTypeDescription(modelValue || '') }}</option>
      </optgroup>
      <option v-if="allowCustom" :value="manualOption">手动声明（原生别名或固定长度数组）</option>
    </select>
    <div v-if="manual" class="manual-type">
      <label :for="`${inputId}-custom`">{{ label }}手动声明</label>
      <input :id="`${inputId}-custom`" :aria-label="`${label}手动声明`" :value="modelValue"
        autocomplete="off" spellcheck="false" placeholder="例如 TEXT、INT 或 ARRAY[60] OF INT"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)" />
      <span class="hint">按当前协议填写，发布时校验支持范围与声明格式。</span>
    </div>
  </div>
</template>

<style scoped>
.manual-type { margin-top: 6px; }
.hint { display: block; color: var(--muted); font-size: 12px; line-height: 1.5; margin-top: 4px; }
</style>
