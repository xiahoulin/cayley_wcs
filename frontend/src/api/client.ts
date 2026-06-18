import axios from 'axios'
import { auth } from '../store/auth'

export interface ApiResponse<T> {
  isSuccess: boolean
  code: number
  errorMessage: string
  data: T
}

export interface PageData<T> {
  rows: T[]
  totals: number
}

const http = axios.create({
  baseURL: import.meta.env.VITE_BASE_API || '/api',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  if (auth.token) {
    config.headers = config.headers || {}
    config.headers['Authorization'] = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    if (error?.response?.status === 401) {
      auth.logout()
    }
    return Promise.reject(error)
  },
)

/** 统一拆 ApiResponse 信封：失败抛出 errorMessage。 */
export async function post<T>(url: string, body?: unknown): Promise<T> {
  const resp = await http.post<ApiResponse<T>>(url, body ?? {})
  const payload = resp.data
  if (!payload.isSuccess) {
    throw new Error(payload.errorMessage || `请求失败(${payload.code})`)
  }
  return payload.data
}

export default http
