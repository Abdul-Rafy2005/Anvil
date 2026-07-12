import { useState, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { JobListItem } from '../types'
import { StatusBadge, PriorityBadge } from '../components/ui/Badges'
import { SkeletonTable, EmptyState, ErrorState } from '../components/ui/Feedback'

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'QUEUED', label: 'Queued' },
  { value: 'RUNNING', label: 'Running' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'FAILED_PERMANENTLY', label: 'Failed permanently' },
  { value: 'RETRYING', label: 'Retrying' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

const JOB_TYPE_OPTIONS = [
  { value: '', label: 'All types' },
  { value: 'CSV_IMPORT', label: 'CSV Import' },
  { value: 'EMAIL_CAMPAIGN', label: 'Email Campaign' },
  { value: 'FILE_COMPRESSION', label: 'File Compression' },
  { value: 'IMAGE_PROCESSING', label: 'Image Processing' },
  { value: 'REPORT_GENERATION', label: 'Report Generation' },
  { value: 'AI_CONTENT_GENERATION', label: 'AI Content' },
]

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

export function JobList() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [jobs, setJobs] = useState<JobListItem[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const page = Number(searchParams.get('page') ?? '0')
  const status = searchParams.get('status') ?? ''
  const jobType = searchParams.get('jobType') ?? ''

  const loadJobs = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.getJobs({ page, size: 20, status: status || undefined, jobType: jobType || undefined })
      setJobs(data.content)
      setTotalPages(data.totalPages)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load jobs')
    } finally {
      setLoading(false)
    }
  }, [page, status, jobType])

  useEffect(() => { loadJobs() }, [loadJobs])

  const updateParam = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key !== 'page') next.delete('page')
    setSearchParams(next)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-zinc-100">Jobs</h1>
        <Link
          to="/jobs/new"
          className="inline-flex items-center gap-2 bg-white text-zinc-950 px-4 py-2 rounded-lg text-sm font-medium hover:bg-zinc-200 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          New Job
        </Link>
      </div>

      <div className="flex gap-2 flex-wrap">
        <select
          value={status}
          onChange={e => updateParam('status', e.target.value)}
          className="px-3 py-1.5 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-300 focus:outline-none focus:border-zinc-600"
        >
          {STATUS_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <select
          value={jobType}
          onChange={e => updateParam('jobType', e.target.value)}
          className="px-3 py-1.5 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-300 focus:outline-none focus:border-zinc-600"
        >
          {JOB_TYPE_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {error && <ErrorState message={error} onRetry={loadJobs} />}

      {!error && loading && <SkeletonTable rows={8} />}

      {!error && !loading && jobs.length === 0 && (
        <EmptyState
          title="No jobs yet"
          description="Submit your first job to see it here. Jobs are processed asynchronously by the worker pool."
          action={
            <Link
              to="/jobs/new"
              className="inline-flex items-center gap-2 bg-white text-zinc-950 px-4 py-2 rounded-lg text-sm font-medium hover:bg-zinc-200 transition-colors"
            >
              Create your first job
            </Link>
          }
        />
      )}

      {!error && !loading && jobs.length > 0 && (
        <>
          <div className="border border-zinc-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-zinc-800 bg-zinc-900/50">
                  <th className="text-left px-4 py-2.5 font-medium text-zinc-500 text-xs uppercase tracking-wider">Type</th>
                  <th className="text-left px-4 py-2.5 font-medium text-zinc-500 text-xs uppercase tracking-wider">Status</th>
                  <th className="text-left px-4 py-2.5 font-medium text-zinc-500 text-xs uppercase tracking-wider">Priority</th>
                  <th className="text-left px-4 py-2.5 font-medium text-zinc-500 text-xs uppercase tracking-wider hidden sm:table-cell">Progress</th>
                  <th className="text-right px-4 py-2.5 font-medium text-zinc-500 text-xs uppercase tracking-wider">Created</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map(job => (
                  <tr key={job.id} className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-800/30 transition-colors">
                    <td className="px-4 py-3">
                      <Link to={`/jobs/${job.id}`} className="text-zinc-200 hover:text-white font-medium">
                        {job.jobType.replace(/_/g, ' ')}
                      </Link>
                      {job.cronExpression && (
                        <span className="block text-xs text-zinc-500 mt-0.5">Cron: {job.cronExpression}</span>
                      )}
                    </td>
                    <td className="px-4 py-3"><StatusBadge status={job.status} /></td>
                    <td className="px-4 py-3"><PriorityBadge priority={job.priority} /></td>
                    <td className="px-4 py-3 hidden sm:table-cell">
                      {job.progressPct !== null ? (
                        <div className="flex items-center gap-2">
                          <div className="w-16 h-1 bg-zinc-800 rounded-full overflow-hidden">
                            <div className="h-full bg-blue-500 rounded-full" style={{ width: `${job.progressPct}%` }} />
                          </div>
                          <span className="text-xs text-zinc-500 tabular-nums">{job.progressPct}%</span>
                        </div>
                      ) : (
                        <span className="text-xs text-zinc-600">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right text-xs text-zinc-500 tabular-nums">{timeAgo(job.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2">
              <button
                onClick={() => updateParam('page', String(page - 1))}
                disabled={page === 0}
                className="px-3 py-1.5 text-sm text-zinc-400 hover:text-zinc-200 disabled:opacity-30 disabled:cursor-not-allowed"
              >
                Previous
              </button>
              <span className="text-sm text-zinc-500 tabular-nums">
                Page {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => updateParam('page', String(page + 1))}
                disabled={page >= totalPages - 1}
                className="px-3 py-1.5 text-sm text-zinc-400 hover:text-zinc-200 disabled:opacity-30 disabled:cursor-not-allowed"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
