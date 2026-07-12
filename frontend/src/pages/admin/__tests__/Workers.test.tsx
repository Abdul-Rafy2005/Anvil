import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Workers } from '../Workers'

const mockGetWorkers = vi.fn()
const mockPauseWorker = vi.fn()
const mockResumeWorker = vi.fn()
const mockRestartWorker = vi.fn()

vi.mock('../../../api/client', () => ({
  api: {
    getWorkers: (...args: unknown[]) => mockGetWorkers(...args),
    pauseWorker: (...args: unknown[]) => mockPauseWorker(...args),
    resumeWorker: (...args: unknown[]) => mockResumeWorker(...args),
    restartWorker: (...args: unknown[]) => mockRestartWorker(...args),
  },
}))

const mockWorkers = [
  { id: 'w1', hostname: 'worker-1', status: 'HEALTHY', currentJobId: 'j1', heartbeatAgeSeconds: 5, startedAt: '2026-01-01T00:00:00Z' },
  { id: 'w2', hostname: 'worker-2', status: 'PAUSED', currentJobId: null, heartbeatAgeSeconds: 120, startedAt: '2026-01-01T00:00:00Z' },
  { id: 'w3', hostname: 'worker-3', status: 'UNHEALTHY', currentJobId: null, heartbeatAgeSeconds: 600, startedAt: '2026-01-01T00:00:00Z' },
]

function renderWorkers() {
  return render(
    <MemoryRouter initialEntries={['/admin/workers']}>
      <Workers />
    </MemoryRouter>
  )
}

describe('Workers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetWorkers.mockResolvedValue(mockWorkers)
  })

  it('renders worker table with all workers', async () => {
    renderWorkers()
    await waitFor(() => {
      expect(screen.getByText('Workers')).toBeInTheDocument()
    })
    expect(screen.getByText('worker-1')).toBeInTheDocument()
    expect(screen.getByText('worker-2')).toBeInTheDocument()
    expect(screen.getByText('worker-3')).toBeInTheDocument()
    expect(screen.getByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('PAUSED')).toBeInTheDocument()
    expect(screen.getByText('UNHEALTHY')).toBeInTheDocument()
  })

  it('shows pause button for HEALTHY workers', async () => {
    renderWorkers()
    await waitFor(() => {
      expect(screen.getByText('worker-1')).toBeInTheDocument()
    })
    const pauseButtons = screen.getAllByText('Pause')
    expect(pauseButtons.length).toBeGreaterThanOrEqual(1)
  })

  it('shows resume button for PAUSED workers', async () => {
    renderWorkers()
    await waitFor(() => {
      expect(screen.getByText('worker-2')).toBeInTheDocument()
    })
    expect(screen.getByText('Resume')).toBeInTheDocument()
  })

  it('shows error state on API failure', async () => {
    mockGetWorkers.mockRejectedValue({ error: { message: 'Forbidden' } })
    renderWorkers()
    await waitFor(() => {
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })
  })

  it('shows empty state when no workers', async () => {
    mockGetWorkers.mockResolvedValue([])
    renderWorkers()
    await waitFor(() => {
      expect(screen.getByText('No workers registered.')).toBeInTheDocument()
    })
  })
})
