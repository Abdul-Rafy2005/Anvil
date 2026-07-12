export function ProgressBar({ pct, message }: { pct: number; message?: string | null }) {
  return (
    <div className="w-full">
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-medium tabular-nums text-zinc-300">{pct}%</span>
        {message && (
          <span className="text-xs text-zinc-500 truncate ml-2 max-w-[200px]">{message}</span>
        )}
      </div>
      <div className="w-full h-1.5 bg-zinc-800 rounded-full overflow-hidden">
        <div
          className="h-full bg-blue-500 rounded-full transition-all duration-500 ease-out"
          style={{ width: `${Math.min(100, Math.max(0, pct))}%` }}
        />
      </div>
    </div>
  )
}
