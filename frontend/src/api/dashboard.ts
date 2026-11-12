import { post } from './client'
import type { ApplicationState } from './deviceState'

export interface DashboardDevice extends ApplicationState { selectedFields: string[]; selectionConfigured: boolean }
export const dashboardDevices = (signal?: AbortSignal) => post<DashboardDevice[]>('/dashboard/devices', {}, signal)
export const saveDashboardSelection = (appId: number, revisionId: number, fields: string[]) =>
  post('/dashboard/selection', { appId, revisionId, fields })
