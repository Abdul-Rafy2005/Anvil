import { statusColors, priorityColors } from '../../tokens'
import type { JobStatus, JobPriority } from '../../types'

export function StatusBadge({ status }: { status: JobStatus }) {
  const c = statusColors[status] ?? statusColors.CREATED
  const label = status.replace(/_/g, ' ')
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${c.bg} ${c.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${c.dot} ${status === 'RUNNING' ? 'animate-pulse' : ''}`} />
      {label}
    </span>
  )
}

export function PriorityBadge({ priority }: { priority: JobPriority }) {
  const c = priorityColors[priority] ?? priorityColors.LOW
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${c.bg} ${c.text}`}>
      {priority}
    </span>
  )
}
