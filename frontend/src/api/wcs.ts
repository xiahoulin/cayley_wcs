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
  conn_params?: unknown
  heartbeat_interval_ms?: number
  enabled?: boolean
  status?: string
  remark?: string
}

export interface DictItem {
  id?: number
  type_code: string
  item_code: string
  item_name: string
  item_value: string
}

export interface ConnectionSnapshot {
  appId: number
  appCode: string
  appName: string
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
export const applicationCreate = (a: Application) => post<Application>('/application/create', a)
export const applicationUpdate = (a: Application) => post<Application>('/application/update', a)
export const applicationDelete = (id: number) => post<boolean>('/application/delete', { id })
export const applicationResetSecret = (id: number) => post<Application>('/application/reset-secret', { id })

// ===== 连接治理 =====
export const connectionStatus = () => post<ConnectionStatus>('/connection/status')
export const connectionOpen = (id: number) => post<ConnectionSnapshot>('/connection/open', { id })
export const connectionClose = (id: number) => post<boolean>('/connection/close', { id })
export const connectionReconnect = (id: number) => post<ConnectionSnapshot>('/connection/reconnect', { id })

// ===== 报警 =====
export const alarmActive = () => post<Alarm[]>('/alarm/active', {})
