<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/client'
import { navigationState, navigationEnable, navigationSend, navigationAcknowledge, type NavigationState } from '../api/agv'
const props = defineProps<{ appId: number; appName: string }>()
const emit = defineEmits<{ close: [] }>()
const state = ref<NavigationState>(), station = ref(''), busy = ref(false), error = ref('')
const attempt = ref<{ appId: number; revisionId: number; configurationEpoch: number; requestId: string; stationName: string }>()
const statusNames = { sending: '发送结果待核实', accepted: '下发成功', rejected: '设备已拒绝', failed: '未发送', unknown: '发送结果待核实' }
async function refresh() {
  try { state.value = await navigationState(props.appId) } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
}
async function enable() {
  busy.value = true; error.value = ''
  try { state.value = await navigationEnable(props.appId) } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
async function send() {
  if (!state.value || busy.value || !station.value.trim()) return
  busy.value = true; error.value = ''
  try {
    // Keep the exact request after an HTTP interruption; retrying must never create a second physical command.
    attempt.value ??= { appId: props.appId, revisionId: state.value.revisionId, configurationEpoch: state.value.configurationEpoch,
      requestId: Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join(''), stationName: station.value.trim() }
    state.value.lastCommand = await navigationSend(attempt.value)
    attempt.value = undefined
  } catch (e) {
    if (e instanceof ApiError && [403, 404, 409, 4223, 4241].includes(e.code)) attempt.value = undefined
    error.value = e instanceof Error ? e.message : String(e); await refresh()
  }
  finally { busy.value = false }
}
async function acknowledge() {
  const command = state.value?.lastCommand
  if (!command || busy.value || !confirm('已核对车辆实际状态、上一条导航及现场工作区，确认可以结束本次下发核对？后台还将检查车辆任务已结束且停稳。')) return
  busy.value = true; error.value = ''
  try { state.value!.lastCommand = await navigationAcknowledge(props.appId, command.requestId); attempt.value = undefined }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
onMounted(refresh)
</script>

<template>
  <div class="modal-mask" @click.self="!busy && emit('close')">
    <section class="panel modal navigation-panel" role="dialog" aria-modal="true" aria-label="导航至站点">
      <div class="toolbar"><h3>导航至站点 · {{ appName }}</h3><span class="spacer"></span><button :disabled="busy" @click="emit('close')">关闭</button></div>
      <p>填写当前地图的站点 ID（如 AP0）。车辆按地图路线和该站点已配置的动作执行。</p>
      <p class="hint">每次仅发送一条导航指令，不抢占控制权。下发成功表示设备已接收，是否到站请查看实时状态。</p>
      <p v-if="error" class="tag err" role="alert">{{ error }}</p>
      <template v-if="state">
        <div v-if="!state.navigationEnabled" class="notice"><p>当前协议仅包含状态采集。启用后增加导航目标和下发结果点位，保留原发布版本。</p><button :disabled="busy || !state.canOperate" @click="enable">启用站点导航协议</button></div>
        <form v-else @submit.prevent="send">
          <div class="field"><label for="navigation-station">站点名称（地图站点 ID）</label><input id="navigation-station" v-model="station" maxlength="128" autocomplete="off" :disabled="busy || !!attempt || !!state.lastCommand?.needsAcknowledgement" /></div>
          <button class="primary" :disabled="busy || !state.canOperate || !station.trim() || !!state.lastCommand?.needsAcknowledgement">{{ busy ? '处理中…' : attempt ? '重试本次导航请求' : '发送导航指令' }}</button>
          <p v-if="attempt" class="hint">重试沿用原请求编号；后台已登记的指令不会再次发送。</p>
        </form>
        <section v-if="state.lastCommand" class="result" aria-live="polite">
          <strong>{{ statusNames[state.lastCommand.status] }}</strong>
          <p>目标站点：{{ state.lastCommand.stationName }}</p><p>{{ state.lastCommand.message }}</p>
          <p v-if="state.lastCommand.retCode != null">设备返回码：{{ state.lastCommand.retCode }}</p>
          <small>下发时间：{{ new Date(state.lastCommand.createdAt).toLocaleString() }}</small>
          <p v-if="state.lastCommand.needsAcknowledgement" class="hint">当前指令未结束核对，已锁定后续下发。请先查看车辆任务和实际状态，确认停稳后解除锁定。</p>
          <button v-if="state.lastCommand.needsAcknowledgement" :disabled="busy || !state.canOperate" @click="acknowledge">核对车辆状态并解除锁定</button>
        </section>
        <p v-if="!state.canOperate" class="hint">当前账号仅可查看，导航操作需要管理员权限。</p>
      </template>
      <button :disabled="busy" @click="refresh">刷新下发结果</button>
    </section>
  </div>
</template>

<style scoped>
.navigation-panel { width: min(680px, 94vw); max-height: 92vh; overflow: auto; }
h3 { margin: 0; }
p { line-height: 1.6; }
.hint { font-size: 13px; color: var(--muted); }
.result, .notice { background: var(--bg); border: 1px solid var(--border); border-radius: 6px; padding: 12px; margin: 16px 0; }
</style>
