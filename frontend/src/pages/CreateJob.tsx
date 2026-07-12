import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { JOB_TYPES } from '../types'

export function CreateJob() {
  const navigate = useNavigate()
  const [jobType, setJobType] = useState('')
  const [payload, setPayload] = useState('{}')
  const [priority, setPriority] = useState('MEDIUM')
  const [schedulingMode, setSchedulingMode] = useState<'immediate' | 'future' | 'cron'>('immediate')
  const [scheduledAt, setScheduledAt] = useState('')
  const [cronExpression, setCronExpression] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const [payloadError, setPayloadError] = useState('')

  function validatePayload(value: string): boolean {
    if (!value.trim()) {
      setPayloadError('Payload is required')
      return false
    }
    try {
      JSON.parse(value)
      setPayloadError('')
      return true
    } catch {
      setPayloadError('Invalid JSON')
      return false
    }
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')

    if (!jobType) {
      setError('Please select a job type')
      return
    }
    if (!validatePayload(payload)) return

    if (schedulingMode === 'future' && !scheduledAt) {
      setError('Please select a date and time')
      return
    }
    if (schedulingMode === 'cron' && !cronExpression.trim()) {
      setError('Please enter a cron expression')
      return
    }

    setLoading(true)
    try {
      const body: {
        jobType: string
        payload: string
        priority?: string
        scheduledAt?: string
        cronExpression?: string
      } = { jobType, payload, priority }

      if (schedulingMode === 'future' && scheduledAt) {
        body.scheduledAt = new Date(scheduledAt).toISOString()
      }
      if (schedulingMode === 'cron' && cronExpression.trim()) {
        body.cronExpression = cronExpression.trim()
      }

      const job = await api.createJob(body)
      navigate(`/jobs/${job.id}`)
    } catch (err: unknown) {
      const apiErr = err as { error?: { message?: string } }
      setError(apiErr?.error?.message ?? 'Failed to create job')
    } finally {
      setLoading(false)
    }
  }

  const selectedType = JOB_TYPES.find(t => t.value === jobType)

  return (
    <div className="max-w-xl mx-auto space-y-6">
      <div>
        <h1 className="text-lg font-semibold text-zinc-100">New Job</h1>
        <p className="text-sm text-zinc-500 mt-1">Submit a job for asynchronous processing</p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-5">
        {error && (
          <div className="bg-red-950/50 border border-red-900 rounded-lg px-3 py-2 text-sm text-red-400">
            {error}
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-2">Job type</label>
          <div className="grid grid-cols-2 gap-2">
            {JOB_TYPES.map(t => (
              <button
                key={t.value}
                type="button"
                onClick={() => setJobType(t.value)}
                className={`text-left px-3 py-2.5 rounded-lg border text-sm transition-colors ${
                  jobType === t.value
                    ? 'border-zinc-600 bg-zinc-800 text-zinc-100'
                    : 'border-zinc-800 bg-zinc-900 text-zinc-400 hover:border-zinc-700 hover:text-zinc-300'
                }`}
              >
                <span className="font-medium">{t.label}</span>
                <span className="block text-xs text-zinc-500 mt-0.5">{t.description}</span>
              </button>
            ))}
          </div>
        </div>

        {selectedType && (
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg px-3 py-2 text-xs text-zinc-500">
            {selectedType.label}: {selectedType.description}
          </div>
        )}

        <div>
          <label htmlFor="payload" className="block text-sm font-medium text-zinc-400 mb-1">
            Payload <span className="text-zinc-600">(JSON)</span>
          </label>
          <textarea
            id="payload"
            value={payload}
            onChange={e => { setPayload(e.target.value); validatePayload(e.target.value) }}
            rows={6}
            className={`w-full px-3 py-2 bg-zinc-900 border rounded-lg text-sm text-zinc-100 font-mono placeholder-zinc-600 focus:outline-none transition-colors ${
              payloadError ? 'border-red-800 focus:border-red-700' : 'border-zinc-800 focus:border-zinc-600'
            }`}
            placeholder='{"key": "value"}'
          />
          {payloadError && (
            <p className="text-xs text-red-400 mt-1">{payloadError}</p>
          )}
        </div>

        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-2">Priority</label>
          <div className="flex gap-2">
            {(['LOW', 'MEDIUM', 'HIGH'] as const).map(p => (
              <button
                key={p}
                type="button"
                onClick={() => setPriority(p)}
                className={`px-4 py-1.5 rounded-lg border text-sm font-medium transition-colors ${
                  priority === p
                    ? p === 'HIGH'
                      ? 'border-red-800 bg-red-950/50 text-red-400'
                      : p === 'MEDIUM'
                      ? 'border-amber-800 bg-amber-950/50 text-amber-400'
                      : 'border-zinc-700 bg-zinc-800 text-zinc-300'
                    : 'border-zinc-800 bg-zinc-900 text-zinc-500 hover:border-zinc-700'
                }`}
              >
                {p}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-2">Scheduling</label>
          <div className="flex gap-2">
            {([
              { value: 'immediate', label: 'Run now' },
              { value: 'future', label: 'Schedule for later' },
              { value: 'cron', label: 'Recurring (cron)' },
            ] as const).map(m => (
              <button
                key={m.value}
                type="button"
                onClick={() => setSchedulingMode(m.value)}
                className={`px-3 py-1.5 rounded-lg border text-sm transition-colors ${
                  schedulingMode === m.value
                    ? 'border-zinc-600 bg-zinc-800 text-zinc-200'
                    : 'border-zinc-800 bg-zinc-900 text-zinc-500 hover:border-zinc-700'
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
        </div>

        {schedulingMode === 'future' && (
          <div>
            <label htmlFor="scheduledAt" className="block text-sm font-medium text-zinc-400 mb-1">
              Run at
            </label>
            <input
              id="scheduledAt"
              type="datetime-local"
              value={scheduledAt}
              onChange={e => setScheduledAt(e.target.value)}
              className="w-full px-3 py-2 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-100 focus:outline-none focus:border-zinc-600"
            />
          </div>
        )}

        {schedulingMode === 'cron' && (
          <div>
            <label htmlFor="cron" className="block text-sm font-medium text-zinc-400 mb-1">
              Cron expression
            </label>
            <input
              id="cron"
              type="text"
              value={cronExpression}
              onChange={e => setCronExpression(e.target.value)}
              className="w-full px-3 py-2 bg-zinc-900 border border-zinc-800 rounded-lg text-sm text-zinc-100 font-mono placeholder-zinc-600 focus:outline-none focus:border-zinc-600"
              placeholder="0 * * * * (every hour)"
            />
            <p className="text-xs text-zinc-600 mt-1">Standard cron format: minute hour day-of-month month day-of-week</p>
          </div>
        )}

        <div className="flex gap-3 pt-2">
          <button
            type="submit"
            disabled={loading}
            className="flex-1 py-2.5 bg-white text-zinc-950 rounded-lg text-sm font-medium hover:bg-zinc-200 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {loading ? 'Submitting...' : 'Submit job'}
          </button>
          <button
            type="button"
            onClick={() => navigate('/jobs')}
            className="px-4 py-2.5 bg-zinc-800 text-zinc-300 rounded-lg text-sm font-medium hover:bg-zinc-700 transition-colors"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
