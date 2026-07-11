function App() {
  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 flex items-center justify-center">
      <div className="text-center space-y-6 max-w-lg px-6">
        <div className="inline-flex items-center gap-2 text-sm font-medium text-zinc-400 bg-zinc-900 border border-zinc-800 rounded-full px-4 py-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
          System Online
        </div>
        <h1 className="text-5xl font-semibold tracking-tight text-white">
          Anvil
        </h1>
        <p className="text-zinc-400 text-lg">
          Distributed job processing &amp; workflow orchestration platform
        </p>
        <div className="pt-4 flex gap-3 justify-center">
          <a
            href="/api/v1/jobs"
            className="inline-flex items-center gap-2 bg-white text-zinc-950 px-5 py-2.5 rounded-lg text-sm font-medium hover:bg-zinc-200 transition-colors"
          >
            View Jobs
          </a>
          <a
            href="/api/v1/admin/stats/overview"
            className="inline-flex items-center gap-2 bg-zinc-800 text-zinc-100 px-5 py-2.5 rounded-lg text-sm font-medium hover:bg-zinc-700 transition-colors border border-zinc-700"
          >
            Admin Dashboard
          </a>
        </div>
      </div>
    </div>
  )
}

export default App
