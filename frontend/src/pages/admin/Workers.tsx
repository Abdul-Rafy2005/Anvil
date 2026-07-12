import { useState, useEffect, useCallback } from 'react'
import { api } from '../../api/client'
import type { WorkerInfo } from '../../types'
import { ErrorState } from '../../components/ui/Feedback'

function formatHeartbeatAge(seconds: number): string {
  if (seconds < 60) return `${Math.floor(seconds)}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  return `${Math.floor(seconds / 3600)}h ago`
}

const statusDot: Record<string, string> = {
  HEALTHY: 'bg-emerald-400',
  PAUSED: 'bg-amber-400',
  UNHEALTHY: 'bg-red-400',
}

export function Workers() {
  const [workers, setWorkers] = useState<WorkerInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [acting, setActing] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setWorkers(await api.getWorkers())
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load workers')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const handleAction = async (id: string, action: 'pause' | 'resume' | 'restart') => {
    setActing(id)
    try {
      if (action === 'pause') await api.pauseWorker(id)
      else if (action === 'resume') await api.resumeWorker(id)
      else await api.restartWorker(id)
      setWorkers(prev => prev.map(w => w.id === id ? { ...w, status: action === 'restart' ? 'HEALTHY' : action === 'pause' ? 'PAUSED' : 'HEALTHY', currentJobId: action === 'restart' ? null : w.currentJobId } : w))
    } catch {
    } finally {
      setActing(null)
    }
  }

  if (error) return <ErrorState message={error} onRetry={load} />

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-zinc-100">Workers</h1>
        <button onClick={load} className="text-sm text-zinc-500 hover:text-zinc-300 transition-colors">Refresh</button>
      </div>

      {loading && workers.length === 0 && (
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-12 bg-zinc-900 border border-zinc-800 rounded-lg animate-pulse" />
          ))}
        </div>
      )}

      {!loading && workers.length === 0 && (
        <p className="text-sm text-zinc-500 py-8 text-center">No workers registered.</p>
      )}

      {workers.length > 0 && (
        <div className="border border-zinc-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-800 bg-zinc-900/50">
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Status</th>
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Hostname</th>
                <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Current Job</th>
                <th className="text-right px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Heartbeat</th>
                <th className="text-right px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Actions</th>
              </tr>
            </thead>
            <tbody>
              {workers.map(w => (
                <tr key={w.id} className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-800/30 transition-colors">
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1.5">
                      <span className={`w-2 h-2 rounded-full ${statusDot[w.status]}`} />
                      <span className="text-zinc-300">{w.status}</span>
                    </span>
                  </td>
                  <td className="px-4 py-3 text-zinc-300 font-mono text-xs">{w.hostname}</td>
                  <td className="px-4 py-3 text-zinc-500 text-xs">
                    {w.currentJobId ? w.currentJobId.slice(0, 8) + '…' : <span className="text-zinc-600">—</span>}
                  </td>
                  <td className="px-4 py-3 text-right text-xs tabular-nums text-zinc-500">
                    {formatHeartbeatAge(w.heartbeatAgeSeconds)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {w.status !== 'PAUSED' && (
                        <button
                          onClick={() => handleAction(w.id, 'pause')}
                          disabled={acting === w.id}
                          className="px-2 py-1 text-xs text-zinc-400 hover:text-amber-400 hover:bg-zinc-800 rounded transition-colors disabled:opacity-50"
                        >
                          Pause
                        </button>
                      )}
                      {w.status === 'PAUSED' && (
                        <button
                          onClick={() => handleAction(w.id, 'resume')}
                          disabled={acting === w.id}
                          className="px-2 py-1 text-xs text-zinc-400 hover:text-emerald-400 hover:bg-zinc-800 rounded transition-colors disabled:opacity-50"
                        >
                          Resume
                        </button>
                      )}
                      <button
                        onClick={() => handleAction(w.id, 'restart')}
                        disabled={acting === w.id}
                        className="px-2 py-1 text-xs text-zinc-400 hover:text-blue-400 hover:bg-zinc-800 rounded transition-colors disabled:opacity-50"
                      >
                        Restart
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
