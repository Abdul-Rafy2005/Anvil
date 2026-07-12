export interface User {
  id: string
  email: string
  role: 'USER' | 'ADMIN'
  isActive: boolean
  createdAt: string
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export type JobStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'FAILED_PERMANENTLY'
  | 'RETRYING'
  | 'CANCELLING'
  | 'CANCELLED'

export type JobPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface Job {
  id: string
  userId: string
  jobType: string
  payload: string
  status: JobStatus
  priority: JobPriority
  progressPct: number | null
  progressMessage: string | null
  result: string | null
  errorMessage: string | null
  attemptCount: number
  maxRetries: number
  scheduledAt: string | null
  cronExpression: string | null
  nextFireAt: string | null
  createdAt: string
  updatedAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface JobListItem {
  id: string
  jobType: string
  status: JobStatus
  priority: JobPriority
  progressPct: number | null
  scheduledAt: string | null
  cronExpression: string | null
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Notification {
  id: string
  jobId: string
  type: string
  message: string
  read: boolean
  createdAt: string
}

export interface NotificationListResponse {
  content: Notification[]
  unreadCount: number
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ApiError {
  error: {
    code: string
    message: string
    traceId: string
  }
}

export interface AdminOverview {
  jobsWaitingByPriority: { HIGH: number; MEDIUM: number; LOW: number }
  jobsRunning: number
  jobsCompletedLast1h: number
  jobsCompletedLast24h: number
  jobsFailedLast1h: number
  jobsFailedLast24h: number
  workersOnline: number
  workersPaused: number
  workersUnhealthy: number
  averageProcessingTimeSeconds: number
  averageProcessingTimeByJobType: Record<string, number>
  queueSize: number
  workerUtilizationPct: number
  dlqSize: number
}

export interface WorkerInfo {
  id: string
  hostname: string
  status: 'HEALTHY' | 'PAUSED' | 'UNHEALTHY'
  currentJobId: string | null
  heartbeatAgeSeconds: number
  startedAt: string
}

export interface DeadLetterEntry {
  id: string
  jobId: string
  jobType: string
  userId: string
  reason: string
  failureHistory: string
  createdAt: string
  resolvedBy: string | null
  resolvedAction: string | null
  resolvedAt: string | null
}

export interface AuditLogEntry {
  id: string
  actorUserId: string
  action: string
  targetType: string
  targetId: string
  metadata: string | null
  createdAt: string
}

export const JOB_TYPES = [
  { value: 'CSV_IMPORT', label: 'CSV Import', description: 'Process CSV data rows' },
  { value: 'EMAIL_CAMPAIGN', label: 'Email Campaign', description: 'Send bulk emails' },
  { value: 'FILE_COMPRESSION', label: 'File Compression', description: 'Compress files into ZIP' },
  { value: 'IMAGE_PROCESSING', label: 'Image Processing', description: 'Convert images to WebP' },
  { value: 'REPORT_GENERATION', label: 'Report Generation', description: 'Generate PDF/CSV reports' },
  { value: 'AI_CONTENT_GENERATION', label: 'AI Content Generation', description: 'Generate text content' },
] as const
