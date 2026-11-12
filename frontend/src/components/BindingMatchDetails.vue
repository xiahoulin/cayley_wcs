<script setup lang="ts">
import type { BindingMatch } from '../api/wcs'
defineProps<{ match: BindingMatch | null; checking?: boolean }>()
const pretty = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value) ?? '-'
</script>

<template>
  <div class="match-details">
    <p v-if="checking" class="muted">正在校验请求与响应点位…</p>
    <template v-else-if="match">
      <span :class="['tag', match.compatible ? 'ok' : 'err']">{{ match.compatible ? '点位匹配通过' : '点位不匹配，无法建立关系' }}</span>
      <small v-if="match.compatible">上位发布版本 #{{ match.upstreamSnapshotId }} → 下位发布版本 #{{ match.downstreamSnapshotId }}</small>
      <div v-if="match.differences.length" class="table-wrap"><table><thead><tr><th>方向 / 字段</th><th>原因</th><th>期望</th><th>实际</th></tr></thead><tbody><tr v-for="(d,i) in match.differences" :key="i"><td>{{ d.direction === 'response' ? '响应' : d.direction === 'request' ? '请求' : d.direction }}<br /><code>{{ d.field || '-' }}</code></td><td>{{ d.reason }}</td><td>{{ pretty(d.expected) }}</td><td>{{ pretty(d.actual) }}</td></tr></tbody></table></div>
    </template>
    <span v-else class="muted">请选择两侧应用并检查点位。保存时后台再次校验。</span>
  </div>
</template>

<style scoped>
.match-details { border: 1px solid var(--border); border-radius: 4px; padding: 9px; margin: 10px 0; font-size: 12px; }
small { color: var(--muted); margin-left: 8px; }
.muted { color: var(--muted); }
.table-wrap { overflow: auto; margin-top: 8px; }
td { white-space: normal; overflow-wrap: anywhere; }
</style>
