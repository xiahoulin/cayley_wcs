import { post, type PageData } from './client'

// ===== 类型 =====
export interface Protocol {
  id?: number
  protocol_code: string
  protocol_name: string
  target_system: string
  protocol_type: string
  data_format?: unknown
  version?: string
  status?: string
  remark?: string
}

export interface ProtocolPoint {
  id?: number
  protocol_id: number
  point_group: string
  field_name: string
  symbol_name: string
  address: string
  data_type: string
  rw: string
  value_range: string
  sort?: number
  description?: string
}

export interface Application {
  id?: number
  app_code: string
  app_name: string
  app_key?: string
  app_secret?: string
  protocol_id: number
  direction: string
  device_type?: string
  work_mode?: string
  dispatch_strategy?: string
  failure_policy?: string
  desired_state?: string
  conn_params?: unknown
  heartbeat_interval_ms?: number
  enabled?: boolean
  status?: string
  remark?: string
}

export interface DictType {
  id?: number
  type_code: string
  type_name: string
  sort?: number
  remark?: string
}

export interface DictItem {
  id?: number
  type_code: string
  item_code: string
  item_name: string
  item_value: string
  sort?: number
  remark?: string
}

export interface FaultCode {
  id?: number
  protocol_id: number
  code: number
  level: string
  name: string
  message: string
  suggestion: string
}

export interface Task {
  id: number
  task_no: string
  app_id: number
  task_type: string
  status: string
  handshake_step: string
  error_code: number
  priority: number
  chain_id?: number
  seq_no?: number
  retry_count?: number
  dispatch_time?: string
  finish_time?: string
}

export interface TaskChain {
  id: number
  chain_no: string
  upstream_app_id: number
  status: string
  select_strategy: string
  priority: number
  error_message?: string
  segments?: Record<string, unknown>[]
  finish_time?: string
}

export interface Webhook {
  id?: number
  name: string
  url: string
  secret?: string
  event_types: string[]
  scope_app_ids: number[]
  enabled?: boolean
  remark?: string
}

export interface WebhookDelivery {
  id: number
  webhook_id: number
  event_type: string
  biz_id: number
  status: string
  attempts: number
  next_retry_time: string
  last_response: string
  payload: string
}

export interface TaskSummary {
  total: number; completed: number; failed: number; cancelled: number
  inFlight: number; pending: number; completionRate: number; avgExecMillis: number
  failTop: { errorCode: number; count: number }[]
}
export interface DeviceUtilization { appId: number; appCode: string; runningMillis: number; windowMillis: number; utilization: number }
export interface AlarmTop { faultCode: number; count: number; level: string; latestMessage: string }

export interface AppBinding {
  id?: number
  upstream_app_id: number
  downstream_app_id: number
  scope?: string
  enabled?: boolean
  remark?: string
}

export interface ConnectionSnapshot {
  appId: number
  appCode: string
  appName: string
  deviceType: string
  protocolType: string
  state: string
  lastHeartbeatAt: number
  retryCount: number
  latest: Record<string, unknown>
}

export interface SlotUsage { max: number; used: number }
export interface ConnectionStatus { connections: ConnectionSnapshot[]; slots: SlotUsage }

export interface Alarm {
  id: number
  app_id: number
  fault_code: number
  level: string
  message: string
  suggestion: string
  status: string
  raised_time: string
}

// ===== 认证 =====
export function login(userName: string, password: string) {
  return post<{ access_token: string; user_name: string }>('/login', { user_name: userName, password })
}

// ===== 字典 =====
export const dictItems = (typeCode: string) => post<DictItem[]>('/dict/item/list-by-type', { typeCode })

// ===== 协议 =====
export const protocolList = (pageIndex = 1, pageSize = 50) =>
  post<PageData<Protocol>>('/protocol/list', { pageIndex, pageSize })
export const protocolAll = () => post<Protocol[]>('/protocol/all')
export const protocolCreate = (p: Protocol) => post<Protocol>('/protocol/create', p)
export const protocolUpdate = (p: Protocol) => post<Protocol>('/protocol/update', p)
export const protocolDelete = (id: number) => post<boolean>('/protocol/delete', { id })
export const protocolPoints = (id: number) => post<ProtocolPoint[]>('/protocol/point/list', { id })

// ===== 应用 =====
export const applicationList = (pageIndex = 1, pageSize = 50) =>
  post<PageData<Application>>('/application/list', { pageIndex, pageSize })
export const applicationAll = () => post<Application[]>('/application/all')
export const applicationCreate = (a: Application) => post<Application>('/application/create', a)
export const applicationUpdate = (a: Application) => post<Application>('/application/update', a)
export const applicationDelete = (id: number) => post<boolean>('/application/delete', { id })
export const applicationResetSecret = (id: number) => post<Application>('/application/reset-secret', { id })

// ===== 连接治理 =====
export const connectionStatus = () => post<ConnectionStatus>('/connection/status')
export const connectionOpen = (id: number) => post<ConnectionSnapshot>('/connection/open', { id })
export const connectionClose = (id: number) => post<boolean>('/connection/close', { id })
export const connectionReconnect = (id: number) => post<ConnectionSnapshot>('/connection/reconnect', { id })

// ===== 上下位绑定授权 =====
export const bindingAll = () => post<AppBinding[]>('/binding/all')
export const bindingCreate = (b: AppBinding) => post<AppBinding>('/binding/create', b)
export const bindingUpdate = (b: AppBinding) => post<AppBinding>('/binding/update', b)
export const bindingDelete = (id: number) => post<boolean>('/binding/delete', { id })
// 按应用授权：查/设 某上位侧应用可指挥的下位侧应用集合
export const bindingGranted = (upstreamAppId: number) =>
  post<number[]>('/binding/granted', { upstreamAppId })
export const bindingGrant = (upstreamAppId: number, downstreamAppIds: number[], scope = 'dispatch') =>
  post<number[]>('/binding/grant', { upstreamAppId, downstreamAppIds, scope })

// ===== 报警 =====
export const alarmActive = () => post<Alarm[]>('/alarm/active', {})
export const alarmList = (pageIndex = 1, pageSize = 100) =>
  post<PageData<Alarm>>('/alarm/list', { pageIndex, pageSize })
export const alarmAck = (id: number, ackBy = 'admin') => post<Alarm>('/alarm/ack', { id, ackBy })
export const alarmClear = (id: number) => post<boolean>('/alarm/clear', { id })
export const alarmResetFault = (appId: number) => post<boolean>('/alarm/reset-fault', { id: appId })

// ===== 字典 =====
export const dictTypeList = () => post<DictType[]>('/dict/type/list', {})
export const dictTypeCreate = (t: DictType) => post<DictType>('/dict/type/create', t)
export const dictTypeUpdate = (t: DictType) => post<DictType>('/dict/type/update', t)
export const dictTypeDelete = (id: number) => post<boolean>('/dict/type/delete', { id })
export const dictItemCreate = (i: DictItem) => post<DictItem>('/dict/item/create', i)
export const dictItemUpdate = (i: DictItem) => post<DictItem>('/dict/item/update', i)
export const dictItemDelete = (id: number) => post<boolean>('/dict/item/delete', { id })

// ===== 故障码 =====
export const faultCodeList = (protocolId: number) => post<FaultCode[]>('/fault-code/list', { id: protocolId })
export const faultCodeCreate = (f: FaultCode) => post<FaultCode>('/fault-code/create', f)
export const faultCodeUpdate = (f: FaultCode) => post<FaultCode>('/fault-code/update', f)
export const faultCodeDelete = (id: number) => post<boolean>('/fault-code/delete', { id })

// ===== 任务 =====
export const taskList = (pageIndex = 1, pageSize = 100) =>
  post<PageData<Task>>('/task/list', { pageIndex, pageSize })
export const taskDetail = (id: number) => post<Task>('/task/detail', { id })
export const taskCancel = (id: number) => post<boolean>('/task/cancel', { id })
export const taskPauseApp = (appId: number) => post<boolean>('/task/pause-app', { appId })
export const taskResumeApp = (appId: number) => post<boolean>('/task/resume-app', { appId })
export const taskReprioritize = (id: number, priority: number) => post<boolean>('/task/reprioritize', { id, priority })
export const taskRetry = (id: number) => post<boolean>('/task/retry', { id })

// ===== 任务链 =====
export const chainList = (pageIndex = 1, pageSize = 100) =>
  post<PageData<TaskChain>>('/chain/list', { pageIndex, pageSize })
export const chainDetail = (id: number) => post<{ chain: TaskChain; tasks: Task[] }>('/chain/detail', { id })
export const chainCancel = (id: number) => post<boolean>('/chain/cancel', { id })

// ===== 统计 =====
export const statsTaskSummary = (appId: number | null, fromMillis = 0, toMillis = 0) =>
  post<TaskSummary>('/stats/task-summary', { appId, fromMillis, toMillis })
export const statsDeviceUtilization = (appId: number | null, fromMillis = 0, toMillis = 0) =>
  post<DeviceUtilization[]>('/stats/device-utilization', { appId, fromMillis, toMillis })
export const statsAlarmTop = (appId: number | null, fromMillis = 0, toMillis = 0, topN = 10) =>
  post<AlarmTop[]>('/stats/alarm-top', { appId, fromMillis, toMillis, topN })

// ===== Webhook =====
export const webhookList = (pageIndex = 1, pageSize = 100) =>
  post<PageData<Webhook>>('/webhook/list', { pageIndex, pageSize })
export const webhookDetail = (id: number) => post<Webhook>('/webhook/detail', { id })
export const webhookCreate = (w: Webhook) => post<Webhook>('/webhook/create', w)
export const webhookUpdate = (w: Webhook) => post<Webhook>('/webhook/update', w)
export const webhookDelete = (id: number) => post<boolean>('/webhook/delete', { id })
export const webhookDeliveries = (webhookId: number, limit = 50) =>
  post<WebhookDelivery[]>('/webhook/deliveries', { webhookId, limit })
