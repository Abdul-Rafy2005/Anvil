import { useState, useEffect, useCallback } from 'react'
import { api } from '../../api/client'
import type { AuditLogEntry } from '../../types'
import { ErrorState } from '../../components/ui/Feedback'

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

const ACTION_OPTIONS = [
  { value: '', label: 'All actions' },
  { value: 'JOB_CREATED', label: 'Job created' },
  { value: 'STATUS_QUEUED_TO_RUNNING', label: 'Status: queued → running' },
  { value: 'STATUS_RUNNING_TO_COMPLETED', label: 'Status: running → completed' },
  { value: 'STATUS_RUNNING_TO_FAILED', label: 'Status: running → failed' },
  { value: 'JOB_CANCELLED', label: 'Job cancelled' },
  { value: 'DLQ_REQUEUE', label: 'DLQ requeue' },
  { value: 'DLQ_DISCARD', label: 'DLQ discard' },
  { value: 'WORKER_PAUSE', label: 'Worker paused' },
  { value: 'WORKER_RESUME', label: 'Worker resumed' },
  { value: 'WORKER_RESTART', label: 'Worker restarted' },
]

export function AuditLog() {
  const [entries, setEntries] = useState<AuditLogEntry[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [actorFilter, setActorFilter] = useState('')
  const [actionFilter, setActionFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.getAuditLog({
        page,
        size: 20,
        actorUserId: actorFilter || undefined,
        action: actionFilter || undefined,
      })
      setEntries(data.content)
      setTotalPages(data.totalPages)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load audit log')
    } finally {
      setLoading(false)
    }
  }, [page, actorFilter, actionFilter])

  useEffect(() => { load() }, [load])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-zinc-100">Audit Log</h1>
        <button onClick={load} className="text-sm text-zinc-500 hover:text-zinc-300 transition-colors">Refresh</button>
      </div>

      <div className="flex gap-2 flex-wrap">
        <input
          type="text"
          placeholder="Actor user ID"
          value={actorFilter}
          onChange={e => { setActorFilter(e.target.value); setPage(0) }}
          className="px-3 py-1.5 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-300 placeholder-zinc-600 focus:outline-none focus:border-zinc-600 w-72"
        />
        <select
          value={actionFilter}
          onChange={e => { setActionFilter(e.target.value); setPage(0) }}
          className="px-3 py-1.5 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-300 focus:outline-none focus:border-zinc-600"
        >
          {ACTION_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {error && <ErrorState message={error} onRetry={load} />}

      {loading && entries.length === 0 && (
        <div className="space-y-3">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-10 bg-zinc-900 border border-zinc-800 rounded animate-pulse" />
          ))}
        </div>
      )}

      {!loading && entries.length === 0 && (
        <p className="text-sm text-zinc-500 py-8 text-center">No audit log entries found.</p>
      )}

      {entries.length > 0 && (
        <div className="border border-zinc-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-800 bg-zinc-900/50">
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Action</th>
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Actor</th>
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Target</th>
                <th className="text-right px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Time</th>
              </tr>
            </thead>
            <tbody>
              {entries.map(entry => (
                <tr key={entry.id} className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-800/30 transition-colors">
                  <td className="px-4 py-2.5">
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-zinc-800 text-zinc-300">
                      {entry.action}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-xs text-zinc-500 font-mono">{entry.actorUserId.slice(0, 8)}…</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-500">
                    {entry.targetType}/{entry.targetId.slice(0, 8)}…
                  </td>
                  <td className="px-4 py-2.5 text-right text-xs text-zinc-500 tabular-nums">{timeAgo(entry.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button onClick={() => setPage(p => p - 1)} disabled={page === 0} className="px-3 py-1.5 text-sm text-zinc-400 hover:text-zinc-200 disabled:opacity-30 disabled:cursor-not-allowed">Previous</button>
          <span className="text-sm text-zinc-500 tabular-nums">Page {page + 1} of {totalPages}</span>
          <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1} className="px-3 py-1.5 text-sm text-zinc-400 hover:text-zinc-200 disabled:opacity-30 disabled:cursor-not-allowed">Next</button>
        </div>
      )}
    </div>
  )
}
