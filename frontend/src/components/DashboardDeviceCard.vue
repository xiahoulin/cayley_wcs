<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardDevice } from '../api/dashboard'
import type { StatePoint } from '../api/deviceState'
import { agvConnectionLabel, gatewayStateLabel } from '../utils/agvLabels'
import { pointQuality, pointValueLabel, qualityLabel } from '../utils/statePoints'
import { pointDisplayName } from '../utils/pointLabels'

const props = defineProps<{ device: DashboardDevice; fetchedAt: number; now: number }>()
defineEmits<{ edit: [device: DashboardDevice] }>()
const points = computed(() => props.device.selectedFields.map(field => props.device.points.find(p => p.field === field)).filter((p): p is StatePoint => !!p))
const quality = (p: StatePoint) => pointQuality(p, props.device, props.fetchedAt, props.now)
const names: Record<string,string> = { stacker:'堆垛机',palletizer:'码垛机',conveyor:'输送线',lift:'提升机',auto_door:'自动门',shuttle:'穿梭车',robot_arm:'机械手',packer:'打包机',agv:'AGV',vibration_sensor:'振动传感器',noise_sensor:'噪声传感器',infrared_sensor:'红外温度传感器' }
const stateLabel = computed(() => props.device.sensorKind ? gatewayStateLabel(props.device.state) : props.device.deviceType === 'agv' ? agvConnectionLabel(props.device.state) : ({RUNNING:'运行中',CONNECTED:'已连接',CONNECTING:'连接中',DISCONNECTED:'已断开',FAILED:'连接失败',CLOSED:'已关闭'}[props.device.state] || '状态未知'))
</script>

<template>
  <section class="panel device" :aria-label="device.appName + '看板'" :data-app-id="device.appId">
    <div class="dev-head"><strong>{{ device.appCode }}</strong><span class="tag muted">{{ names[device.deviceType] || device.deviceType }}</span><span v-if="device.workMode !== 'normal'" :class="['tag', device.workMode === 'maintenance' ? 'err' : 'warn']">{{ device.workMode === 'maintenance' ? '维护' : '暂停' }}</span><span class="spacer"></span><button class="edit-display" :aria-label="'看板展示编辑：' + device.appName" @click="$emit('edit', device)">看板展示编辑</button></div>
    <div class="dev-sub"><span class="app-name">{{ device.appName }}</span><span :class="['tag', ['RUNNING','CONNECTED','ONLINE'].includes(device.state) ? 'ok' : device.state === 'FAILED' ? 'err' : 'muted']">{{ stateLabel }}</span></div>
    <div class="metrics-scroll">
      <dl v-if="points.length" class="metrics-grid">
        <div v-for="p in points" :key="p.field" class="metric-cell" :data-field="p.field">
          <dt>{{ pointDisplayName(p) }}</dt><dd><template v-if="quality(p) === 'GOOD' && p.value != null">{{ pointValueLabel(p) }}<small v-if="p.unit"> {{ p.unit }}</small></template><span v-else class="unavailable">{{ qualityLabel(quality(p)) }}</span></dd>
        </div>
      </dl>
      <div v-else class="empty">尚未选择展示点位，点击右上方“看板展示编辑”进行选择。</div>
    </div>
  </section>
</template>

<style scoped>
.device { min-width: 0; height: 400px; padding: 12px 14px; display: flex; flex-direction: column; overflow: hidden; }
.dev-head { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; flex-shrink: 0; }
.dev-head strong { min-width: 0; overflow-wrap: anywhere; }
.edit-display { font-size: 11px; padding: 4px 7px; white-space: nowrap; }
.dev-sub { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 10px; flex-shrink: 0; }
.app-name { min-width: 0; color: var(--muted); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.dev-sub .tag { flex-shrink: 0; }
.metrics-scroll { min-height: 0; min-width: 0; overflow: auto; flex: 1; }
.metrics-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--border); border-left: 1px solid var(--border); }
.metric-cell { min-width: 0; padding: 8px; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); }
dt { color: var(--muted); font-size: 12px; margin-bottom: 4px; line-height: 1.5; overflow-wrap: anywhere; }
dd { min-width: 0; margin: 0; font-size: 14px; line-height: 1.6; font-weight: 600; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
dd small { font-weight: 400; color: var(--muted); font-size: 11px; }
.unavailable { font-size: 12px; font-weight: 400; color: var(--muted); }
.empty { color: var(--muted); font-size: 12px; line-height: 1.7; padding: 18px 0; }
</style>
