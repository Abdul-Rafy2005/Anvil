import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AdminOverview } from '../Overview'

const mockGetAdminOverview = vi.fn()

vi.mock('../../../api/client', () => ({
  api: {
    getAdminOverview: (...args: unknown[]) => mockGetAdminOverview(...args),
  },
}))

const mockOverview = {
  jobsWaitingByPriority: { HIGH: 3, MEDIUM: 5, LOW: 2 },
  jobsRunning: 8,
  jobsCompletedLast1h: 12,
  jobsCompletedLast24h: 150,
  jobsFailedLast1h: 1,
  jobsFailedLast24h: 7,
  workersOnline: 3,
  workersPaused: 1,
  workersUnhealthy: 0,
  averageProcessingTimeSeconds: 12.5,
  averageProcessingTimeByJobType: { CSV_IMPORT: 8.3, REPORT_GENERATION: 22.1 },
  queueSize: 10,
  workerUtilizationPct: 72.5,
  dlqSize: 2,
}

function renderOverview() {
  return render(
    <MemoryRouter initialEntries={['/admin']}>
      <AdminOverview />
    </MemoryRouter>
  )
}

describe('AdminOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetAdminOverview.mockResolvedValue(mockOverview)
  })

  it('renders stat tiles with correct values', async () => {
    renderOverview()
    await waitFor(() => {
      expect(screen.getByText('System Overview')).toBeInTheDocument()
    })
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('150')).toBeInTheDocument()
    expect(screen.getByText('72.5%')).toBeInTheDocument()
    expect(screen.getByText('12.5s')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('renders job type breakdown table', async () => {
    renderOverview()
    await waitFor(() => {
      expect(screen.getByText('CSV IMPORT')).toBeInTheDocument()
    })
    expect(screen.getByText('8.3s')).toBeInTheDocument()
    expect(screen.getByText('REPORT GENERATION')).toBeInTheDocument()
    expect(screen.getByText('22.1s')).toBeInTheDocument()
  })

  it('shows error state on API failure', async () => {
    mockGetAdminOverview.mockRejectedValue({ error: { message: 'Forbidden' } })
    renderOverview()
    await waitFor(() => {
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })
    expect(screen.getByText('Forbidden')).toBeInTheDocument()
  })

  it('shows loading skeleton initially', () => {
    mockGetAdminOverview.mockReturnValue(new Promise(() => {}))
    const { container } = renderOverview()
    expect(container.querySelector('.animate-pulse')).toBeInTheDocument()
  })
})
