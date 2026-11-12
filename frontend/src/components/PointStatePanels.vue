<script setup lang="ts">
import { computed } from 'vue'
import type { ApplicationState, StatePoint } from '../api/deviceState'
import { pointGroup, pointQuality, pointValueLabel, qualityLabel } from '../utils/statePoints'
import { pointTypeDescription } from '../utils/pointTypes'

const props = defineProps<{ state: ApplicationState; points: StatePoint[]; now: number; fetchedAt: number; locked: boolean }>()
defineEmits<{ set: [point: StatePoint] }>()
const groups = computed(() => {
  const grouped = new Map<string, StatePoint[]>()
  for (const point of props.points) {
    const name = pointGroup(point, props.state)
    if (!grouped.has(name)) grouped.set(name, [])
    grouped.get(name)!.push(point)
  }
  return Array.from(grouped, ([name, points]) => ({ name, points }))
})
const quality = (point: StatePoint) => pointQuality(point, props.state, props.fetchedAt, props.now)
const date = (value?: number | null) => value == null ? '尚未接收数据' : new Date(value).toLocaleString()
</script>

<template>
  <div class="point-panels">
    <section v-for="group in groups" :key="group.name" class="state-group" :aria-label="group.name">
      <div class="group-heading"><h4>{{ group.name }}</h4><span>{{ group.points.length }} 个点位</span></div>
      <div class="point-grid">
        <article v-for="point in group.points" :key="point.field" class="point-card" :data-field="point.field">
          <div class="point-heading">
            <h5>{{ point.label }}</h5>
            <button v-if="point.writable" class="set-button" :aria-label="'设置' + point.label" :disabled="!point.canSet || locked" :title="point.reason || (locked ? '正在更新状态或提交设置' : '设置此点位')" @click="$emit('set', point)">设置</button>
            <span v-else class="access-label">{{ point.readable ? '只读' : '内部控制' }}</span>
          </div>
          <div :class="['point-value', { unavailable: quality(point) !== 'GOOD', 'text-value': typeof point.value !== 'number' && typeof point.value !== 'boolean' }]">
            <template v-if="quality(point) === 'GOOD' && point.value != null">
              <span>{{ pointValueLabel(point) }}</span><small v-if="point.unit" class="unit">{{ point.unit }}</small>
            </template><span v-else>—</span>
          </div>
          <div class="point-status"><span :class="['quality-dot', { good: quality(point) === 'GOOD', warning: !['GOOD', 'NO_READBACK', 'UNKNOWN'].includes(quality(point)) }]"></span>{{ qualityLabel(quality(point)) }}<span class="spacer"></span><span v-if="point.canSet" class="editable-label">可设置</span></div>
          <div class="received-at">{{ date(point.receivedAt) }}</div>
          <p v-if="point.writable && !point.canSet" class="gate-reason">{{ point.reason || '暂不可设置' }}</p>
          <details v-if="quality(point) !== 'GOOD' && (point.sampleValue != null || point.lastGood)" class="sample-detail"><summary>查看采集记录</summary><p v-if="point.sampleValue != null">最近接收值：{{ point.sampleValue }} {{ point.unit }}（{{ qualityLabel(quality(point)) }}）</p><p v-if="point.lastGood">上次有效值：{{ point.lastGood.value }} {{ point.unit }} · {{ date(point.lastGood.receivedAt) }}</p></details>
          <div class="point-meta"><span>{{ point.field }}</span><span :title="point.type">{{ pointTypeDescription(point.type) }}</span></div>
        </article>
      </div>
    </section>
    <p v-if="!groups.length" class="empty-state">无符合条件的点位</p>
  </div>
</template>

<style scoped>
.point-panels { padding: 2px 2px 4px; }
.state-group + .state-group { margin-top: 14px; }
.group-heading { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; }
.group-heading h4 { font-size: 14px; margin: 0; border-left: 3px solid var(--primary); padding-left: 8px; }
.group-heading > span { color: var(--muted); font-size: 12px; }
.point-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 6px; }
.point-card { min-width: 0; border: 1px solid var(--border); border-radius: 6px; background: var(--panel); padding: 6px 10px; display: flex; flex-direction: column; }
.point-heading { display: flex; align-items: center; gap: 6px; min-height: 20px; }
.point-heading h5 { font-size: 12px; line-height: 16px; font-weight: 500; margin: 0; flex: 1; overflow-wrap: anywhere; }
.access-label { color: var(--muted); font-size: 10px; white-space: nowrap; }
.set-button { font-size: 11px; line-height: 16px; padding: 2px 8px; border-color: #f6d4b2; color: #b65500; background: #fff8f0; flex-shrink: 0; }
.set-button:hover { background: #ffeddb; }
.point-value { display: flex; gap: 4px; align-items: baseline; margin: 2px 0 4px; font-size: 19px; line-height: 24px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; white-space: pre-wrap; }
.point-value > span { min-width: 0; min-height: 26px; max-height: 160px; overflow: auto; }
.point-value.text-value { font-size: 16px; }
.point-value.unavailable { color: #a2a9b3; }
.unit { color: var(--muted); font-size: 11px; font-weight: 400; white-space: nowrap; }
.point-status { display: flex; align-items: center; gap: 5px; color: var(--muted); font-size: 11px; line-height: 14px; margin-top: auto; }
.quality-dot { width: 6px; height: 6px; border-radius: 50%; background: #a2a9b3; flex-shrink: 0; }
.quality-dot.good { background: var(--ok); } .quality-dot.warning { background: var(--warn); }
.editable-label { color: var(--ok); } .received-at { margin-top: 1px; color: var(--muted); font-size: 10px; line-height: 12px; }
.point-meta { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 2px 6px; border-top: 1px solid #eef0f2; margin-top: 4px; padding-top: 3px; color: var(--muted); font-size: 10px; line-height: 12px; overflow-wrap: anywhere; }
.gate-reason { color: var(--warn); margin: 8px 0 0; font-size: 12px; }
.sample-detail { font-size: 12px; color: var(--muted); margin-top: 8px; overflow-wrap: anywhere; }
summary { cursor: pointer; } .empty-state { text-align: center; color: var(--muted); padding: 36px; }
</style>
