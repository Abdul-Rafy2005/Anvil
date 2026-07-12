import { useState, useEffect, useCallback } from 'react'
import { api } from '../../api/client'
import type { DeadLetterEntry } from '../../types'
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

export function Dlq() {
  const [entries, setEntries] = useState<DeadLetterEntry[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [acting, setActing] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.getDLQ({ page, size: 20, resolved: false })
      setEntries(data.content)
      setTotalPages(data.totalPages)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load DLQ')
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => { load() }, [load])

  const handleRequeue = async (id: string) => {
    setActing(id)
    try {
      await api.requeueDLQ(id)
      setEntries(prev => prev.filter(e => e.id !== id))
    } catch {
    } finally {
      setActing(null)
    }
  }

  const handleDiscard = async (id: string) => {
    setActing(id)
    try {
      await api.discardDLQ(id)
      setEntries(prev => prev.filter(e => e.id !== id))
    } catch {
    } finally {
      setActing(null)
    }
  }

  if (error) return <ErrorState message={error} onRetry={load} />

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-zinc-100">Dead Letter Queue</h1>
        <button onClick={load} className="text-sm text-zinc-500 hover:text-zinc-300 transition-colors">Refresh</button>
      </div>

      {loading && entries.length === 0 && (
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-16 bg-zinc-900 border border-zinc-800 rounded-lg animate-pulse" />
          ))}
        </div>
      )}

      {!loading && entries.length === 0 && (
        <p className="text-sm text-zinc-500 py-8 text-center">No entries in the dead letter queue.</p>
      )}

      {entries.length > 0 && (
        <div className="space-y-2">
          {entries.map(entry => (
            <div key={entry.id} className="border border-zinc-800 rounded-lg overflow-hidden">
              <div className="flex items-center justify-between px-4 py-3 bg-zinc-900/50 hover:bg-zinc-800/30 transition-colors cursor-pointer" onClick={() => setExpanded(expanded === entry.id ? null : entry.id)}>
                <div className="flex items-center gap-4">
                  <span className="text-sm text-zinc-200 font-medium">{entry.jobType.replace(/_/g, ' ')}</span>
                  <span className="text-xs text-zinc-500">Job: {entry.jobId.slice(0, 8)}…</span>
                  <span className="text-xs text-zinc-500">{entry.reason.slice(0, 60)}{entry.reason.length > 60 ? '…' : ''}</span>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-xs text-zinc-500 tabular-nums">{timeAgo(entry.createdAt)}</span>
                  <svg className={`w-4 h-4 text-zinc-500 transition-transform ${expanded === entry.id ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                  </svg>
                </div>
              </div>
              {expanded === entry.id && (
                <div className="px-4 py-3 border-t border-zinc-800 bg-zinc-900/30">
                  <p className="text-xs text-zinc-400 mb-2">Full reason: <span className="text-zinc-300">{entry.reason}</span></p>
                  {entry.failureHistory && (() => {
                    try {
                      const history = JSON.parse(entry.failureHistory)
                      return (
                        <div className="mb-3">
                          <p className="text-xs text-zinc-500 mb-1">Attempts: {history.attemptCount} / {history.maxRetries}</p>
                          {history.attempts?.map((a: { attemptNumber: number; status: string; error: string | null }, i: number) => (
                            <div key={i} className="text-xs text-zinc-500 ml-2">
                              <span className="text-zinc-400">#{a.attemptNumber}</span> {a.status}
                              {a.error && <span className="text-red-400/70 ml-1">— {a.error.slice(0, 80)}</span>}
                            </div>
                          ))}
                        </div>
                      )
                    } catch {
                      return <pre className="text-xs text-zinc-500 max-h-32 overflow-auto">{entry.failureHistory}</pre>
                    }
                  })()}
                  <div className="flex gap-2">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleRequeue(entry.id) }}
                      disabled={acting === entry.id}
                      className="px-3 py-1.5 text-xs bg-zinc-800 text-zinc-300 hover:text-zinc-100 hover:bg-zinc-700 rounded transition-colors disabled:opacity-50"
                    >
                      Requeue
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleDiscard(entry.id) }}
                      disabled={acting === entry.id}
                      className="px-3 py-1.5 text-xs bg-zinc-800 text-red-400/70 hover:text-red-400 hover:bg-zinc-700 rounded transition-colors disabled:opacity-50"
                    >
                      Discard
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
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
