// 显示术语及依据见 docs/agv-readonly-telemetry.md；设备原始字段和值保持不变。
const fieldLabels: Record<string, string> = {
  x: '地图 X 坐标（m）', y: '地图 Y 坐标（m）', angle: '车体朝向角（rad）',
  confidence: '定位置信度', similarity: '定位匹配相似度',
  current_station: '当前所在站点', last_station: '上一经过站点',
  loc_method: '定位方式代码', loc_state: '定位状态代码', loc_notify: '定位提示信息',
  in_forbidden_area: '是否位于禁行区域', update_reason: '定位更新原因代码',
  correction_errs: '定位修正反馈', reliabilities: '定位可靠性信息',
  vx: '车体 X 方向速度（m/s）', vy: '车体 Y 方向速度（m/s）', w: '车体角速度（rad/s）',
  r_vx: '接收的 X 方向速度指令', r_vy: '接收的 Y 方向速度指令', r_w: '接收的角速度指令',
  steer: '舵轮转向角', steer_angles: '各舵轮转向角', motor_steer_angles: '各转向电机角度',
  r_steer: '舵轮转向指令', r_steer_angles: '各舵轮转向指令',
  spin: '旋转反馈量', r_spin: '旋转指令量',
  is_stop: '是否处于停止状态', detect_skid: '打滑检测标志',
  task_status: '导航任务状态', task_type: '导航任务类型', running_status: '导航运行状态代码',
  target_id: '目标站点编号', target_label: '目标站点名称', target_point: '目标位置与朝向',
  target_dist: '距目标的剩余距离', finished_path: '已通过站点', unfinished_path: '待通过站点',
  containers: '载具信息', move_status_info: '运动状态说明', info: '说明', objectFile: '识别文件',
  create_on: '设备响应生成时间', ret_code: '接口返回码', err_msg: '接口错误说明',
  agvAppId: '关联车辆编号', capturedAt: '工况记录时间', quality: '数据质量',
  connectionState: '状态连接情况', ageMs: '数据距接收时长（ms）', queries: '车辆工况',
  seer_location: '定位与站点', seer_speed: '运动状态', seer_navigation: '导航任务',
  api: '查询接口编号', sequence: '报文序号', receivedAt: '服务器接收时间',
  headerMode: '响应报文格式', payload: '设备状态数据',
}

const connectionLabels: Record<string, string> = {
  NEW: '尚未连接', CONNECTING: '正在连接', CONNECTED: '已建立连接', RUNNING: '状态采集中',
  DISCONNECTED: '连接已中断', RECONNECTING: '正在重新连接', FAILED: '连接失败', CLOSED: '连接已关闭',
}
const gatewayLabels: Record<string, string> = {
  STOPPED: '采集已停用', DRAINING: '等待总线就绪', ONLINE: '通信正常', DEGRADED: '通信异常', OFFLINE: '通信已中断', UNKNOWN: '通信状态未知',
}
const storageLabels: Record<string, string> = { OK: '正常', RETRYING: '写入重试中' }
const qualityLabels: Record<string, string> = {
  GOOD: '有效', UNKNOWN: '未知', STALE: '数据已过期', WARMUP_UNKNOWN: '供电时间未知',
  WARMING_UP: '预热中', TIMEOUT: '通信超时', PROTOCOL_ERROR: '协议异常', OUT_OF_RANGE: '超出量程',
}

// 任务枚举来自厂商历史协议；不将其套用到 running_status 或定位扩展状态。
const taskStatuses: Record<number, string> = { 0: '无任务', 1: '等待执行', 2: '执行中', 3: '已暂停', 4: '已完成', 5: '执行失败', 6: '已取消' }
const taskTypes: Record<number, string> = { 0: '无任务', 1: '自由导航至坐标点', 2: '自由导航至站点', 3: '沿固定路径导航至站点', 4: '巡检', 100: '其他任务' }

export const agvFieldLabel = (field: string) => fieldLabels[field] ?? '设备扩展信息'
export const agvConnectionLabel = (state?: string) => state ? connectionLabels[state] ?? '连接状态未知' : '连接状态未知'
export const gatewayStateLabel = (state?: string) => state ? gatewayLabels[state] ?? '通信状态未知' : '未配置'
export const storageStateLabel = (state?: string) => state ? storageLabels[state] ?? '存储状态未知' : '存储状态未知'
export const agvQualityLabel = (state?: string) => qualityLabels[state ?? 'UNKNOWN'] ?? '未知'

export function agvFieldValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '未提供'
  // 本车的运动状态说明是 JSON 字符串，其他固件也可能直接返回对象。
  if (field === 'move_status_info' && typeof value === 'string') {
    try {
      const decoded: unknown = JSON.parse(value)
      if (decoded && typeof decoded === 'object') return agvFieldValue(field, decoded)
    } catch { /* 非结构化说明保留原文。 */ }
  }
  if (field === 'connectionState') return agvConnectionLabel(String(value))
  if (field === 'quality') return agvQualityLabel(String(value))
  if (field === 'headerMode') return value === 'zero-reserved' ? '标准保留区格式' : value === 'echo-request-api' ? '请求接口编号回显格式' : '未识别的报文格式'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number') {
    if (field === 'task_status') return `${taskStatuses[value] ?? '未识别的任务状态'}（${value}）`
    if (field === 'task_type') return `${taskTypes[value] ?? '未识别的任务类型'}（${value}）`
    if (field === 'ret_code') return `${value === 0 ? '请求成功' : '请求失败'}（${value}）`
    if (field === 'capturedAt' || field === 'receivedAt') return new Date(value).toLocaleString()
  }
  if (Array.isArray(value)) return value.length ? value.map(item => agvFieldValue('', item)).join('、') : '无'
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    return entries.length ? entries.map(([key, item]) => `${fieldLabels[key] ?? `未识别字段（${key}）`}：${agvFieldValue(key, item)}`).join('\n') : '无'
  }
  return String(value)
}

export function agvProfileLabel(version?: string): string {
  if (!version) return '未提供'
  const match = /^three-sensors-v1\/(\d+)$/.exec(version)
  return match ? `三传感器采集档案第 1 版 · 配置版本 ${match[1]}` : version
}
