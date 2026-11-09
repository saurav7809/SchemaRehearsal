import axios from 'axios'
import { useCallback, useEffect, useState } from 'react'

export const api = axios.create({ baseURL: '/api', timeout: 30000 })
export const sessionExpiredEvent = 'schema-rehearsal:session-expired'

api.interceptors.request.use(async config => {
  if (['post', 'put', 'delete', 'patch'].includes(config.method ?? '')) {
    const { data } = await api.get<{ token: string; headerName: string }>('/auth/csrf', { signal: config.signal })
    config.headers.set(data.headerName, data.token)
  }
  return config
})

const publicAuthPaths = new Set(['/auth/csrf', '/auth/register', '/auth/login'])

api.interceptors.response.use(undefined, error => {
  if (axios.isAxiosError(error) && error.response?.status === 401 &&
      !publicAuthPaths.has(error.config?.url ?? '')) {
    window.dispatchEvent(new Event(sessionExpiredEvent))
  }
  return Promise.reject(error)
})

export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 401) return 'Check your credentials or sign in again.'
    if (status === 429) return 'Too many requests. Wait a moment and try again.'
    const message = error.response?.data?.message
    if (typeof message === 'string' && message.trim()) return message
    if (status === 403) return 'This request was denied. Refresh the page and try again.'
    if (status === 404) return 'This resource is unavailable.'
    if (status === 409) return 'The resource has changed. Refresh the page and try again.'
    if (error.code === 'ECONNABORTED') return 'The request timed out. Please try again.'
    if (!status) return 'The service could not be reached. Check your connection and try again.'
    return 'The request could not be completed. Please try again.'
  }
  return 'The request could not be completed.'
}

export function useResource<T>(url: string) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState('')
  const [status, setStatus] = useState<number>()
  const [failures, setFailures] = useState(0)
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(0)
  const reload = useCallback(() => setVersion(x => x + 1), [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    api.get<T>(url, { signal: controller.signal }).then(response => {
      if (controller.signal.aborted) return
      setData(response.data)
      setError('')
      setStatus(response.status)
      setFailures(0)
    })
      .catch(e => {
        if (controller.signal.aborted) return
        setError(errorMessage(e))
        setStatus(axios.isAxiosError(e) ? e.response?.status : undefined)
        setFailures(count => count + 1)
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [url, version])
  return { data, error, status, failures, loading, reload }
}

export type Project = { id: number; name: string; description: string; postgresVersion: string; createdAt: string }
type MigrationFile = { name: string; sql: string }
export type Migration = { id: number; projectId: number; name: string; sql: string | null; createdAt: string; files?: MigrationFile[] }
export type Probe = { id: number; name: string; generation: 'OLD' | 'NEW'; sql: string }
export type Finding = { source: string; severity: string; title: string; filename?: string; table?: string; column?: string; statement?: string; evidence: string; recommendation: string }
export type QueryResult = { success: boolean; columns: string[]; sampledRows: number; error?: string }
export type Run = {
  id: number; projectId: number; migrationId: number; migrationName: string; status: string; score: number; durationMs: number; createdAt: string
  report: {
    activeStep?: string; error?: string; cleanupError?: string; findings?: Finding[]
    steps?: { name: string; status: string; durationMs: number }[]
    migrationFiles?: { name: string; status: 'APPLIED' | 'FAILED' | 'PENDING'; durationMs: number | null; error: string | null }[]
    changes?: { object: string; kind: string; before?: string; after?: string }[]
    risk?: { score: number; classification: string; uncapped: number; factors: { source: string; title: string; points: number }[] }
    compatibility?: { id: number; name: string; generation: string; status: string; baseline?: QueryResult; migrated?: QueryResult }[]
  }
}

export const pending = (run: Run) => ['QUEUED', 'RUNNING'].includes(run.status)
export const risk = (score: number) => score <= 20 ? 'LOW' : score <= 40 ? 'MEDIUM' : score <= 70 ? 'HIGH' : 'CRITICAL'
export const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
