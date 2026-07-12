import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Job } from '../types'
import { StatusBadge, PriorityBadge } from '../components/ui/Badges'
import { ProgressBar } from '../components/ui/ProgressBar'
import { ErrorState } from '../components/ui/Feedback'
import { useJobWebSocket } from '../hooks/useWebSocket'

function formatDate(s: string | null): string {
  if (!s) return '-'
  return new Date(s).toLocaleString()
}

function canCancel(status: string): boolean {
  return ['CREATED', 'QUEUED', 'RUNNING'].includes(status)
}

function tryParseJson(s: string): unknown | null {
  try { return JSON.parse(s) } catch { return null }
}

function ResultDetail({ job }: { job: Job }) {
  if (job.status !== 'COMPLETED' || !job.result) return null
  const parsed = tryParseJson(job.result) as Record<string, unknown> | null
  const jobType = job.jobType

  if (parsed && jobType === 'AI_CONTENT_GENERATION') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="bg-zinc-950 rounded p-3 max-h-64 overflow-y-auto">
          <p className="text-sm text-zinc-200 leading-relaxed whitespace-pre-wrap">
            {String(parsed.generatedText ?? job.result)}
          </p>
        </div>
        <div className="flex gap-4 text-xs text-zinc-500">
          {typeof parsed.model === 'string' && <span>Model: <span className="text-zinc-400">{parsed.model}</span></span>}
          {typeof parsed.tokenCount === 'number' && (
            <span>Tokens: <span className="text-zinc-400 tabular-nums">{parsed.tokenCount}</span></span>
          )}
        </div>
      </div>
    )
  }

  if (parsed && jobType === 'CSV_IMPORT') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatTile label="Rows processed" value={String(parsed.rowsProcessed ?? 0)} />
          <StatTile label="Errors" value={String(parsed.errors ?? 0)} error={Number(parsed.errors ?? 0) > 0} />
          <StatTile label="Status" value={String(parsed.duration ?? 'completed')} />
        </div>
      </div>
    )
  }

  if (parsed && jobType === 'EMAIL_CAMPAIGN') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatTile label="Sent" value={String(parsed.sent ?? 0)} />
          <StatTile label="Bounced" value={String(parsed.bounced ?? 0)} error={Number(parsed.bounced ?? 0) > 0} />
          <StatTile label="Campaign ID" value={String(parsed.campaignId ?? '-').slice(0, 8)} mono />
        </div>
      </div>
    )
  }

  if (parsed && jobType === 'FILE_COMPRESSION') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatTile label="Original" value={String(parsed.originalSize ?? '-')} />
          <StatTile label="Compressed" value={String(parsed.compressedSize ?? '-')} />
          <StatTile label="Ratio" value={String(parsed.ratio ?? '-')} />
        </div>
        <button
          disabled
          className="w-full py-2 border border-zinc-800 rounded-lg text-sm text-zinc-500 bg-zinc-950 cursor-not-allowed"
        >
          Simulated download — {String(parsed.archiveUrl ?? 'archive')}
        </button>
      </div>
    )
  }

  if (parsed && jobType === 'IMAGE_PROCESSING') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatTile label="Images converted" value={String(parsed.convertedCount ?? 0)} />
          <StatTile label="Output format" value={String(parsed.outputFormat ?? '-')} />
          <StatTile label="Output dir" value={String(parsed.outputDir ?? '-')} mono />
        </div>
      </div>
    )
  }

  if (parsed && jobType === 'REPORT_GENERATION') {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-medium text-zinc-400">Result</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatTile label="Format" value={String(parsed.format ?? '-')} />
          <StatTile label="Pages" value={String(parsed.pages ?? '-')} />
          <StatTile label="Report" value={String(parsed.reportUrl ?? '-')} mono />
        </div>
        <button
          disabled
          className="w-full py-2 border border-zinc-800 rounded-lg text-sm text-zinc-500 bg-zinc-950 cursor-not-allowed"
        >
          Simulated download — {String(parsed.reportUrl ?? 'report')}
        </button>
      </div>
    )
  }

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-sm font-medium text-zinc-400 mb-2">Result</h3>
      <pre className="text-xs text-zinc-300 font-mono whitespace-pre-wrap break-all bg-zinc-950 rounded p-3 overflow-x-auto max-h-48">
        {job.result}
      </pre>
    </div>
  )
}

function StatTile({ label, value, error, mono }: {
  label: string
  value: string
  error?: boolean
  mono?: boolean
}) {
  return (
    <div className="bg-zinc-950 rounded-lg px-3 py-2.5">
      <p className="text-xs text-zinc-500 mb-0.5">{label}</p>
      <p className={`text-sm font-medium tabular-nums ${mono ? 'font-mono text-xs' : ''} ${error ? 'text-red-400' : 'text-zinc-200'}`}>
        {value}
      </p>
    </div>
  )
}

export function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [job, setJob] = useState<Job | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)

  const { progress, status: wsStatus, message: wsMessage } = useJobWebSocket(id ?? null)

  const loadJob = useCallback(async () => {
    if (!id) return
    setLoading(true)
    setError(null)
    try {
      const data = await api.getJob(id)
      setJob(data)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load job')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => { loadJob() }, [loadJob])

  useEffect(() => {
    if (!job) return
    if (wsStatus) setJob(prev => prev ? { ...prev, status: wsStatus as Job['status'] } : prev)
    if (wsMessage) setJob(prev => prev ? { ...prev, progressMessage: wsMessage } : prev)
    if (progress > 0) setJob(prev => prev ? { ...prev, progressPct: progress } : prev)
  }, [wsStatus, wsMessage, progress])

  const handleCancel = async () => {
    if (!job) return
    setCancelling(true)
    try {
      const updated = await api.cancelJob(job.id)
      setJob(updated)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      alert(apiErr?.error?.message ?? 'Failed to cancel job')
    } finally {
      setCancelling(false)
    }
  }

  if (loading) {
    return (
      <div className="max-w-xl mx-auto space-y-4">
        <div className="h-6 w-32 bg-zinc-800 rounded animate-pulse" />
        <div className="h-4 w-48 bg-zinc-800 rounded animate-pulse" />
        <div className="h-32 bg-zinc-800 rounded animate-pulse" />
      </div>
    )
  }

  if (error || !job) {
    return (
      <div className="max-w-xl mx-auto">
        <ErrorState message={error ?? 'Job not found'} onRetry={loadJob} />
      </div>
    )
  }

  const displayPct = progress > 0 ? progress : (job.progressPct ?? 0)
  const displayMsg = wsMessage ?? job.progressMessage

  return (
    <div className="max-w-xl mx-auto space-y-6">
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/jobs')}
          className="text-zinc-500 hover:text-zinc-300 transition-colors"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
          </svg>
        </button>
        <div>
          <h1 className="text-lg font-semibold text-zinc-100">
            {job.jobType.replace(/_/g, ' ')}
          </h1>
          <p className="text-xs text-zinc-500 font-mono">{job.id}</p>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <StatusBadge status={job.status} />
        <PriorityBadge priority={job.priority} />
        {job.attemptCount > 0 && (
          <span className="text-xs text-zinc-500">
            Attempt {job.attemptCount}/{job.maxRetries}
          </span>
        )}
      </div>

      {(job.status === 'RUNNING' || displayPct > 0) && (
        <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
          <ProgressBar pct={displayPct} message={displayMsg} />
        </div>
      )}

      {job.status === 'COMPLETED' && job.result && (
        <ResultDetail job={job} />
      )}

      {(job.status === 'FAILED' || job.status === 'FAILED_PERMANENTLY') && job.errorMessage && (
        <div className="bg-red-950/30 border border-red-900/50 rounded-lg p-4">
          <h3 className="text-sm font-medium text-red-400 mb-2">Error</h3>
          <pre className="text-xs text-red-300/80 font-mono whitespace-pre-wrap break-all">
            {job.errorMessage}
          </pre>
        </div>
      )}

      <div className="bg-zinc-900 border border-zinc-800 rounded-lg divide-y divide-zinc-800">
        <DetailRow label="Status" value={job.status.replace(/_/g, ' ')} />
        <DetailRow label="Priority" value={job.priority} />
        <DetailRow label="Job type" value={job.jobType.replace(/_/g, ' ')} />
        <DetailRow label="Created" value={formatDate(job.createdAt)} />
        <DetailRow label="Updated" value={formatDate(job.updatedAt)} />
        <DetailRow label="Started" value={formatDate(job.startedAt)} />
        <DetailRow label="Completed" value={formatDate(job.completedAt)} />
        {job.scheduledAt && <DetailRow label="Scheduled for" value={formatDate(job.scheduledAt)} />}
        {job.cronExpression && <DetailRow label="Cron" value={job.cronExpression} />}
        {job.nextFireAt && <DetailRow label="Next fire" value={formatDate(job.nextFireAt)} />}
      </div>

      {job.payload && job.payload !== '{}' && (
        <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
          <h3 className="text-sm font-medium text-zinc-400 mb-2">Payload</h3>
          <pre className="text-xs text-zinc-300 font-mono whitespace-pre-wrap break-all bg-zinc-950 rounded p-3 overflow-x-auto max-h-32">
            {(() => { try { return JSON.stringify(JSON.parse(job.payload), null, 2) } catch { return job.payload } })()}
          </pre>
        </div>
      )}

      {canCancel(job.status) && (
        <button
          onClick={handleCancel}
          disabled={cancelling}
          className="w-full py-2.5 border border-red-900 text-red-400 rounded-lg text-sm font-medium hover:bg-red-950/30 disabled:opacity-50 transition-colors"
        >
          {cancelling ? 'Cancelling...' : 'Cancel job'}
        </button>
      )}
    </div>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between px-4 py-2.5">
      <span className="text-sm text-zinc-500">{label}</span>
      <span className="text-sm text-zinc-300 font-mono text-right">{value}</span>
    </div>
  )
}
