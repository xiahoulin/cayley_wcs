import { post } from './client'
import type { Measurement } from './agv'

export interface MonitoredApplication {
  appId: number; appCode: string; appName: string; deviceType: string; protocolType: string
  revisionId: number; configurationEpoch: number; enabled: boolean; workMode: string
  desiredState: string; state: string; connected: boolean; lastHeartbeatAt: number | null; retryCount: number
  gatewayId?: number; sensorKind?: string; agvAppId?: number
  adapterType?: string
}
export interface StatePoint {
  field: string; label: string; type: string; scope: string; address: string; description: string; unit: string
  constraints: Record<string, unknown>; readable: boolean; writable: boolean; canSet: boolean; reason: string
  value: unknown; sampleValue: unknown; quality: string; receivedAt: number | null; sourceField?: string
  raw?: number | null; lastGood?: Measurement | null
}
export interface ApplicationState extends MonitoredApplication { points: StatePoint[]; serverTime: number; staleAfterMs: number; settingReason: string }
export const monitoredApplications = () => post<MonitoredApplication[]>('/connection/applications')
export const applicationState = (id: number) => post<ApplicationState>('/connection/state', { id })
export const setApplicationState = (state: ApplicationState, payload: Record<string, unknown>) =>
  post('/connection/state/set', { appId: state.appId, revisionId: state.revisionId, configurationEpoch: state.configurationEpoch, payload })
