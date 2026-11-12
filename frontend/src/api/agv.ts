import { post, type PageData } from './client'

export interface AgvState {
  appId: number
  appCode: string
  appName: string
  endpointRef: string
  motionEnabled: boolean
  connectionState: string
  quality: string
  ageMs: number | null
  queries: Record<string, { api: number; receivedAt: string; payload: Record<string, unknown> }>
}
export interface Gateway {
  id: number
  agv_app_id: number
  endpoint_ref: string
  enabled: boolean
  infrared_offset: number
  infrared_min: number
  infrared_max: number
  power_on_at: number | null
  power_evidence: string | null
  config_version: number
}
export interface Measurement {
  metric: string
  raw: number | null
  value: number | null
  unit: string
  quality: string
  sourceQuality?: string
  receivedAt?: number
  profileVersion?: string
  ageMs?: number | null
  context?: Record<string, unknown>
  lastGood?: Measurement | null
}
export interface Telemetry {
  applicationManaged: boolean
  activeUnits: number[]
  gatewayId: number
  endpointConfigured: boolean
  connectionState: string
  configVersion: number
  measurements: Record<string, Measurement>
  diagnostics?: Record<string, number | string>
  storage: { queued: number; dropped: number; persistence: string }
  serverTime: number
}
export interface Cycle {
  id: number
  cycle_id: string
  started_at: number
  finished_at: number
  duration_ms: number
  connection_state: string
  measurements: Record<string, Measurement>
}
export const agvList = () => post<AgvState[]>('/agv/list')
export const agvUpgrade = (appId: number) => post<AgvState>('/agv/upgrade', { appId })
export interface NavigationCommand {
  requestId: string; stationName: string; sent: boolean; status: 'sending' | 'accepted' | 'rejected' | 'failed' | 'unknown'
  retCode: number | null; message: string; createdAt: number; receivedAt: number | null; needsAcknowledgement: boolean
}
export interface NavigationState {
  appId: number; revisionId: number; configurationEpoch: number; navigationEnabled: boolean; canOperate: boolean; lastCommand: NavigationCommand | null
}
export const navigationState = (appId: number) => post<NavigationState>('/agv/navigation/state', { appId })
export const navigationEnable = (appId: number) => post<NavigationState>('/agv/navigation/enable', { appId })
export const navigationSend = (body: { appId: number; revisionId: number; configurationEpoch: number; requestId: string; stationName: string }) => post<NavigationCommand>('/agv/navigation/send', body)
export const navigationAcknowledge = (appId: number, requestId: string) => post<NavigationCommand>('/agv/navigation/acknowledge', { appId, requestId })
export const gatewayApplications = (gatewayId: number) => post('/telemetry/gateway/applications', { gatewayId })
export const agvCreate = (body: { appCode: string; appName: string; endpointRef: string }) => post<AgvState>('/agv/create', body)
export const gatewayList = (agvAppId: number) => post<Gateway[]>('/telemetry/gateway/list', { agvAppId })
export const gatewayConfigure = (body: { id?: number; agvAppId: number; endpointRef: string; infraredOffset: number; infraredMin: number; infraredMax: number; expectedVersion?: number }) => post<Gateway>('/telemetry/gateway/configure', body)
export const gatewayEnable = (gateway: Gateway, enabled: boolean) => post<Gateway>('/telemetry/gateway/enable', { id: gateway.id, enabled, expectedVersion: gateway.config_version })
export const gatewayPower = (gateway: Gateway, powerOnAt: number | null, evidence: string) => post<Gateway>('/telemetry/gateway/power', { id: gateway.id, powerOnAt, evidence, expectedVersion: gateway.config_version })
export const telemetryLatest = (gatewayId: number) => post<Telemetry>('/telemetry/latest', { gatewayId })
export const telemetryHistory = (gatewayId: number, beforeId?: number) => post<PageData<Cycle>>('/telemetry/history', { gatewayId, beforeId, limit: 100 })
