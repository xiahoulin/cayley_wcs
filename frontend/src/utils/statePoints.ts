import type { ApplicationState, StatePoint } from '../api/deviceState'
import { agvFieldValue, agvQualityLabel } from './agvLabels'
import { parsePointJson } from './pointJson'

export function pointQuality(point: StatePoint, state: ApplicationState, fetchedAt: number, now: number) {
  const serverNow = state.serverTime + Math.max(0, now - fetchedAt)
  return point.readable && point.receivedAt != null && serverNow - point.receivedAt > state.staleAfterMs ? 'STALE' : point.quality
}

export const qualityLabel = (quality: string) => quality === 'NO_READBACK' ? '未提供回读' : quality === 'UNCONFIGURED' ? '待配置采集端点' : agvQualityLabel(quality)
export const pointValueLabel = (point: StatePoint) => agvFieldValue(point.sourceField ?? point.field, point.value)

export function pointGroup(point: StatePoint, state: ApplicationState): string {
  if (point.scope !== 'business') return '内部诊断'
  if (state.deviceType === 'agv') {
    if (point.field.startsWith('loc_')) return '定位与站点'
    if (point.field.startsWith('speed_')) return '运动状态'
    if (point.field.startsWith('nav_')) return '导航任务'
  }
  if (state.sensorKind || ['vibration_sensor','noise_sensor','infrared_sensor'].includes(state.deviceType)) {
    if (point.field.startsWith('vibration_speed_')) return '振动速度'
    if (point.field.startsWith('vibration_displacement_')) return '振动位移'
    if (point.field.startsWith('vibration_frequency_')) return '振动频率'
    if (point.field === 'vibration_chip_temperature') return '芯片温度'
    return '测量数据'
  }
  return point.writable ? '可设置参数' : '设备状态'
}

export function parseStatePointInput(point: StatePoint, input: string): unknown {
  if (point.type === 'STRING') return validateStatePointValue(point, input)
  if (!input.trim()) throw new Error('请填写设置值')
  if (point.constraints?.wire_encoding === 'decimal_string') return validateStatePointValue(point, input.trim())
  let value: unknown
  try { value = parsePointJson(input) }
  catch (e) {
    if (e instanceof SyntaxError) throw new Error(point.type.startsWith('ARRAY') ? '请填写有效的 JSON 数组，例如 [1, 2]' : '请填写有效的数值')
    throw e
  }
  return validateStatePointValue(point, value)
}

/** Immediate input feedback; the published contract and write permissions are rechecked by the server. */
export function validateStatePointValue(point: StatePoint, value: unknown): unknown {
  const rules = point.constraints ?? {}
  const integer = /^(U?)INT(8|16|32|64)$/
  function scalar(type: string, v: unknown, wireString = false) {
    if (type === 'BOOL') { if (typeof v !== 'boolean') throw new Error('请选择是或否'); return }
    if (type === 'STRING') { if (typeof v !== 'string') throw new Error('请填写文字'); return }
    const match = integer.exec(type)
    if (match) {
      if (wireString) {
        if (typeof v !== 'string' || !/^-?(0|[1-9]\d*)$/.test(v)) throw new Error('请填写十进制整数')
      } else if (typeof v !== 'number' || !Number.isSafeInteger(v)) throw new Error('请填写安全范围内的整数')
      const n = BigInt(v as string | number), bits = BigInt(match[2]!)
      const low = match[1] ? 0n : -(1n << (bits - 1n)), high = (1n << (match[1] ? bits : bits - 1n)) - 1n
      if (n < low || n > high) throw new Error(`整数范围为 ${low} 至 ${high}`)
      return
    }
    if (!['FLOAT32', 'FLOAT64', 'DECIMAL'].includes(type)) throw new Error('该点位类型暂不支持编辑')
    if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error('请填写有效数值')
    if (type === 'FLOAT32' && !Number.isFinite(Math.fround(v))) throw new Error('数值超出单精度浮点数范围')
  }
  if (point.type.startsWith('ARRAY<')) {
    if (!Array.isArray(value)) throw new Error('请填写数组')
    const elementType = point.type.slice(6, -1), dimensions = Array.isArray(rules.dimensions) ? rules.dimensions : []
    function array(items: unknown[], level: number) {
      if (dimensions.length && items.length !== Number(dimensions[level])) throw new Error('数组维度或长度不符合点位要求')
      for (const item of items) {
        if (level + 1 < dimensions.length) {
          if (!Array.isArray(item)) throw new Error('数组维度不符合点位要求')
          array(item, level + 1)
        } else scalar(elementType, item)
      }
    }
    array(value, 0)
    if (rules.length != null && value.length !== Number(rules.length)) throw new Error(`数组长度必须为 ${rules.length}`)
    if (rules.max_items != null && value.length > Number(rules.max_items)) throw new Error(`数组最多包含 ${rules.max_items} 项`)
  } else scalar(point.type, value, rules.wire_encoding === 'decimal_string')
  if (point.type === 'STRING') {
    const length = Array.from(value as string).length
    if (rules.min_length != null && length < Number(rules.min_length)) throw new Error(`至少输入 ${rules.min_length} 个字符`)
    if (rules.max_length != null && length > Number(rules.max_length)) throw new Error(`最多输入 ${rules.max_length} 个字符`)
  }
  if (rules.wire_encoding === 'decimal_string') {
    const n = BigInt(value as string)
    if (rules.min != null && n < BigInt(String(rules.min))) throw new Error(`不能小于 ${rules.min}`)
    if (rules.max != null && n > BigInt(String(rules.max))) throw new Error(`不能大于 ${rules.max}`)
  } else if (typeof value === 'number') {
    if (rules.min != null && value < Number(rules.min)) throw new Error(`不能小于 ${rules.min}`)
    if (rules.max != null && value > Number(rules.max)) throw new Error(`不能大于 ${rules.max}`)
  }
  if (Array.isArray(rules.enum) && !rules.enum.some(item => JSON.stringify(item) === JSON.stringify(value))) throw new Error('请选择允许的参数值')
  return value
}

export function linkedDoorPoint(state: ApplicationState, point: StatePoint) {
  if (state.deviceType !== 'auto_door' || !['cmd_Open', 'cmd_Close'].includes(point.field)) return undefined
  return state.points.find(p => p.field === (point.field === 'cmd_Open' ? 'cmd_Close' : 'cmd_Open'))
}

export function stateSettingPayload(state: ApplicationState, point: StatePoint, value: unknown) {
  if (!point.writable || !point.canSet) throw new Error(point.reason || '该点位当前不可设置')
  const payload: Record<string, unknown> = { [point.field]: validateStatePointValue(point, value) }
  if (state.deviceType === 'auto_door' && ['cmd_Open', 'cmd_Close'].includes(point.field)) {
    const linked = linkedDoorPoint(state, point)
    if (!linked?.writable || !linked.canSet) throw new Error('自动门联动点位当前不可设置')
    if (value !== 0 && value !== 1) throw new Error('自动门指令必须为 0 或 1')
    payload[linked.field] = validateStatePointValue(linked, 1 - value)
  }
  return payload
}
