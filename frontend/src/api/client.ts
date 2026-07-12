import type {
  AuthResponse,
  User,
  Job,
  JobListItem,
  PageResponse,
  NotificationListResponse,
  AdminOverview,
  WorkerInfo,
  DeadLetterEntry,
  AuditLogEntry,
} from '../types'

const BASE = '/api/v1'

class ApiClient {
  private accessToken: string | null = null
  private refreshToken: string | null = null

  constructor() {
    this.accessToken = localStorage.getItem('accessToken')
    this.refreshToken = localStorage.getItem('refreshToken')
  }

  setTokens(access: string, refresh: string) {
    this.accessToken = access
    this.refreshToken = refresh
    localStorage.setItem('accessToken', access)
    localStorage.setItem('refreshToken', refresh)
  }

  clearTokens() {
    this.accessToken = null
    this.refreshToken = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
  }

  isAuthenticated(): boolean {
    return !!this.accessToken
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string>),
    }

    if (this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`
    }

    const res = await fetch(`${BASE}${path}`, { ...options, headers })

    if (res.status === 401 && this.refreshToken) {
      const refreshed = await this.tryRefresh()
      if (refreshed) {
        headers['Authorization'] = `Bearer ${this.accessToken}`
        const retryRes = await fetch(`${BASE}${path}`, { ...options, headers })
        if (!retryRes.ok) {
          const body = await retryRes.json().catch(() => null)
          throw body ?? { error: { code: 'UNKNOWN', message: `Request failed: ${retryRes.status}` } }
        }
        return retryRes.json()
      }
      this.clearTokens()
      window.location.href = '/login'
      throw new Error('Session expired')
    }

    if (!res.ok) {
      const body = await res.json().catch(() => null)
      throw body ?? { error: { code: 'UNKNOWN', message: `Request failed: ${res.status}` } }
    }

    if (res.status === 204) return undefined as T
    return res.json()
  }

  private async tryRefresh(): Promise<boolean> {
    try {
      const res = await fetch(`${BASE}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: this.refreshToken }),
      })
      if (!res.ok) return false
      const data: AuthResponse = await res.json()
      this.setTokens(data.accessToken, data.refreshToken)
      return true
    } catch {
      return false
    }
  }

  async register(email: string, password: string): Promise<User> {
    return this.request<User>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  }

  async login(email: string, password: string): Promise<AuthResponse> {
    const data = await this.request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    this.setTokens(data.accessToken, data.refreshToken)
    return data
  }

  async logout() {
    if (this.refreshToken) {
      await this.request('/auth/logout', {
        method: 'POST',
        body: JSON.stringify({ refreshToken: this.refreshToken }),
      }).catch(() => {})
    }
    this.clearTokens()
  }

  async getMe(): Promise<User> {
    return this.request<User>('/users/me')
  }

  async getJobs(params: {
    page?: number
    size?: number
    status?: string
    jobType?: string
  }): Promise<PageResponse<JobListItem>> {
    const qs = new URLSearchParams()
    if (params.page !== undefined) qs.set('page', String(params.page))
    if (params.size !== undefined) qs.set('size', String(params.size))
    if (params.status) qs.set('status', params.status)
    if (params.jobType) qs.set('jobType', params.jobType)
    return this.request<PageResponse<JobListItem>>(`/jobs?${qs}`)
  }

  async getJob(id: string): Promise<Job> {
    return this.request<Job>(`/jobs/${id}`)
  }

  async createJob(data: {
    jobType: string
    payload: string
    priority?: string
    scheduledAt?: string
    cronExpression?: string
  }): Promise<Job> {
    return this.request<Job>('/jobs', {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async cancelJob(id: string): Promise<Job> {
    return this.request<Job>(`/jobs/${id}/cancel`, { method: 'POST' })
  }

  async getNotifications(params: {
    page?: number
    size?: number
  } = {}): Promise<NotificationListResponse> {
    const qs = new URLSearchParams()
    if (params.page !== undefined) qs.set('page', String(params.page))
    if (params.size !== undefined) qs.set('size', String(params.size))
    return this.request<NotificationListResponse>(`/notifications?${qs}`)
  }

  async markNotificationRead(id: string): Promise<void> {
    await this.request(`/notifications/${id}/read`, { method: 'POST' })
  }

  getAccessToken(): string | null {
    return this.accessToken
  }

  async getAdminOverview(): Promise<AdminOverview> {
    return this.request<AdminOverview>('/admin/stats/overview')
  }

  async getWorkers(): Promise<WorkerInfo[]> {
    return this.request<WorkerInfo[]>('/admin/workers')
  }

  async pauseWorker(id: string): Promise<WorkerInfo> {
    return this.request<WorkerInfo>(`/admin/workers/${id}/pause`, { method: 'POST' })
  }

  async resumeWorker(id: string): Promise<WorkerInfo> {
    return this.request<WorkerInfo>(`/admin/workers/${id}/resume`, { method: 'POST' })
  }

  async restartWorker(id: string): Promise<WorkerInfo> {
    return this.request<WorkerInfo>(`/admin/workers/${id}/restart`, { method: 'POST' })
  }

  async getDLQ(params: {
    page?: number
    size?: number
    jobType?: string
    resolved?: boolean
  } = {}): Promise<PageResponse<DeadLetterEntry>> {
    const qs = new URLSearchParams()
    if (params.page !== undefined) qs.set('page', String(params.page))
    if (params.size !== undefined) qs.set('size', String(params.size))
    if (params.jobType) qs.set('jobType', params.jobType)
    if (params.resolved !== undefined) qs.set('resolved', String(params.resolved))
    return this.request<PageResponse<DeadLetterEntry>>(`/admin/dlq?${qs}`)
  }

  async requeueDLQ(id: string): Promise<DeadLetterEntry> {
    return this.request<DeadLetterEntry>(`/admin/dlq/${id}/requeue`, { method: 'POST' })
  }

  async discardDLQ(id: string): Promise<DeadLetterEntry> {
    return this.request<DeadLetterEntry>(`/admin/dlq/${id}/discard`, { method: 'POST' })
  }

  async getAuditLog(params: {
    page?: number
    size?: number
    actorUserId?: string
    action?: string
    since?: string
    until?: string
  } = {}): Promise<{ content: AuditLogEntry[]; page: number; size: number; totalElements: number; totalPages: number }> {
    const qs = new URLSearchParams()
    if (params.page !== undefined) qs.set('page', String(params.page))
    if (params.size !== undefined) qs.set('size', String(params.size))
    if (params.actorUserId) qs.set('actorUserId', params.actorUserId)
    if (params.action) qs.set('action', params.action)
    if (params.since) qs.set('since', params.since)
    if (params.until) qs.set('until', params.until)
    return this.request(`/admin/audit-log?${qs}`)
  }
}

export const api = new ApiClient()
