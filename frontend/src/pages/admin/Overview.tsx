import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import type { AdminOverview } from '../../types'
import { ErrorState } from '../../components/ui/Feedback'

function StatTile({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <p className="text-xs text-zinc-500 uppercase tracking-wider mb-1">{label}</p>
      <p className="text-2xl font-semibold text-zinc-100 tabular-nums">{value}</p>
      {sub && <p className="text-xs text-zinc-500 mt-1 tabular-nums">{sub}</p>}
    </div>
  )
}

function SkeletonTiles() {
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i} className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 animate-pulse">
          <div className="h-3 w-16 bg-zinc-800 rounded mb-2" />
          <div className="h-7 w-12 bg-zinc-800 rounded" />
        </div>
      ))}
    </div>
  )
}

export function AdminOverview() {
  const [data, setData] = useState<AdminOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await api.getAdminOverview())
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to load admin overview')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
    const interval = setInterval(load, 5000)
    return () => clearInterval(interval)
  }, [load])

  if (error) return <ErrorState message={error} onRetry={load} />
  if (loading && !data) return <SkeletonTiles />
  if (!data) return null

  const totalWorkers = data.workersOnline + data.workersPaused + data.workersUnhealthy

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-zinc-100">System Overview</h1>
        <span className="text-xs text-zinc-500 tabular-nums">Auto-refreshing every 5s</span>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatTile label="Queue Size" value={data.queueSize} sub={`H ${data.jobsWaitingByPriority.HIGH} · M ${data.jobsWaitingByPriority.MEDIUM} · L ${data.jobsWaitingByPriority.LOW}`} />
        <StatTile label="Running" value={data.jobsRunning} />
        <StatTile label="Completed (1h)" value={data.jobsCompletedLast1h} />
        <StatTile label="Completed (24h)" value={data.jobsCompletedLast24h} />
        <StatTile label="Failed (1h)" value={data.jobsFailedLast1h} />
        <StatTile label="Failed (24h)" value={data.jobsFailedLast24h} />
        <StatTile label="Workers" value={totalWorkers} sub={`↑ ${data.workersOnline}  ⏸ ${data.workersPaused}  ⚠ ${data.workersUnhealthy}`} />
        <StatTile label="Utilization" value={`${data.workerUtilizationPct}%`} />
        <StatTile label="Avg Processing" value={`${data.averageProcessingTimeSeconds}s`} />
        <StatTile label="DLQ Size" value={data.dlqSize} />
      </div>

      {Object.keys(data.averageProcessingTimeByJobType).length > 0 && (
        <div>
          <h2 className="text-sm font-medium text-zinc-400 mb-3">Avg Processing Time by Type</h2>
          <div className="border border-zinc-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-zinc-800 bg-zinc-900/50">
                  <th className="text-left px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Job Type</th>
                  <th className="text-right px-4 py-2 font-medium text-zinc-500 text-xs uppercase tracking-wider">Avg Time</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(data.averageProcessingTimeByJobType).map(([type, time]) => (
                  <tr key={type} className="border-b border-zinc-800/50 last:border-0">
                    <td className="px-4 py-2 text-zinc-300">{type.replace(/_/g, ' ')}</td>
                    <td className="px-4 py-2 text-right text-zinc-400 tabular-nums">{time}s</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="flex gap-4">
        <Link to="/admin/workers" className="text-sm text-zinc-400 hover:text-zinc-200 underline underline-offset-2">Manage Workers</Link>
        <Link to="/admin/dlq" className="text-sm text-zinc-400 hover:text-zinc-200 underline underline-offset-2">Dead Letter Queue</Link>
        <Link to="/admin/audit" className="text-sm text-zinc-400 hover:text-zinc-200 underline underline-offset-2">Audit Log</Link>
      </div>
    </div>
  )
}
