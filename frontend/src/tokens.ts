export const colors = {
  bg: {
    primary: 'bg-zinc-950',
    secondary: 'bg-zinc-900',
    tertiary: 'bg-zinc-800',
    hover: 'bg-zinc-800',
    elevated: 'bg-zinc-900',
  },
  text: {
    primary: 'text-zinc-100',
    secondary: 'text-zinc-400',
    tertiary: 'text-zinc-500',
    muted: 'text-zinc-600',
  },
  border: {
    primary: 'border-zinc-800',
    secondary: 'border-zinc-700',
    focus: 'border-zinc-500',
  },
  accent: {
    primary: 'bg-white',
    primaryText: 'text-zinc-950',
    primaryHover: 'hover:bg-zinc-200',
    secondary: 'bg-zinc-800',
    secondaryText: 'text-zinc-100',
    secondaryHover: 'hover:bg-zinc-700',
  },
} as const

export const statusColors: Record<string, { bg: string; text: string; dot: string }> = {
  CREATED: { bg: 'bg-zinc-800', text: 'text-zinc-400', dot: 'bg-zinc-500' },
  QUEUED: { bg: 'bg-amber-950', text: 'text-amber-400', dot: 'bg-amber-400' },
  RUNNING: { bg: 'bg-blue-950', text: 'text-blue-400', dot: 'bg-blue-400' },
  COMPLETED: { bg: 'bg-emerald-950', text: 'text-emerald-400', dot: 'bg-emerald-400' },
  FAILED: { bg: 'bg-red-950', text: 'text-red-400', dot: 'bg-red-400' },
  FAILED_PERMANENTLY: { bg: 'bg-red-950', text: 'text-red-400', dot: 'bg-red-400' },
  RETRYING: { bg: 'bg-amber-950', text: 'text-amber-400', dot: 'bg-amber-400' },
  CANCELLING: { bg: 'bg-orange-950', text: 'text-orange-400', dot: 'bg-orange-400' },
  CANCELLED: { bg: 'bg-zinc-800', text: 'text-zinc-500', dot: 'bg-zinc-500' },
}

export const priorityColors: Record<string, { bg: string; text: string }> = {
  HIGH: { bg: 'bg-red-950', text: 'text-red-400' },
  MEDIUM: { bg: 'bg-amber-950', text: 'text-amber-400' },
  LOW: { bg: 'bg-zinc-800', text: 'text-zinc-400' },
}
