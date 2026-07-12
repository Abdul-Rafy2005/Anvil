import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { JobList } from '../JobList'

const mockGetJobs = vi.fn()

vi.mock('../../api/client', () => ({
  api: {
    getJobs: (...args: unknown[]) => mockGetJobs(...args),
    isAuthenticated: () => true,
    getMe: () => Promise.resolve({ id: 'u1', email: 'test@test.com', role: 'USER', isActive: true, createdAt: '' }),
  },
}))

const mockJobs = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    jobType: 'CSV_IMPORT',
    status: 'QUEUED',
    priority: 'HIGH',
    progressPct: null,
    scheduledAt: null,
    cronExpression: null,
    createdAt: new Date().toISOString(),
  },
  {
    id: '22222222-2222-2222-2222-222222222222',
    jobType: 'REPORT_GENERATION',
    status: 'RUNNING',
    priority: 'MEDIUM',
    progressPct: 45,
    scheduledAt: null,
    cronExpression: null,
    createdAt: new Date().toISOString(),
  },
  {
    id: '33333333-3333-3333-3333-333333333333',
    jobType: 'EMAIL_CAMPAIGN',
    status: 'COMPLETED',
    priority: 'LOW',
    progressPct: 100,
    scheduledAt: null,
    cronExpression: null,
    createdAt: new Date().toISOString(),
  },
]

function renderJobList(urlSearch = '') {
  return render(
    <MemoryRouter initialEntries={[`/jobs${urlSearch ? `?${urlSearch}` : ''}`]}>
      <JobList />
    </MemoryRouter>
  )
}

describe('JobList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetJobs.mockResolvedValue({
      content: mockJobs,
      number: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    })
  })

  it('renders job list with all jobs', async () => {
    renderJobList()
    expect(await screen.findByText('CSV Import')).toBeInTheDocument()
    expect(screen.getByText('Report Generation')).toBeInTheDocument()
    expect(screen.getByText('Email Campaign')).toBeInTheDocument()
  })

  it('shows empty state when no jobs', async () => {
    mockGetJobs.mockResolvedValue({
      content: [],
      number: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    })
    renderJobList()
    expect(await screen.findByText('No jobs yet')).toBeInTheDocument()
    expect(screen.getByText('Create your first job')).toBeInTheDocument()
  })

  it('shows loading skeleton', () => {
    mockGetJobs.mockReturnValue(new Promise(() => {}))
    renderJobList()
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows error state with retry', async () => {
    mockGetJobs.mockRejectedValue({ error: { message: 'Server error' } })
    renderJobList()
    expect(await screen.findByText('Server error')).toBeInTheDocument()
    expect(screen.getByText('Try again')).toBeInTheDocument()
  })

  it('calls getJobs with status filter when URL has status param', async () => {
    renderJobList('status=RUNNING')
    await waitFor(() => {
      expect(mockGetJobs).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'RUNNING' })
      )
    })
  })

  it('calls getJobs with jobType filter when URL has jobType param', async () => {
    renderJobList('jobType=CSV_IMPORT')
    await waitFor(() => {
      expect(mockGetJobs).toHaveBeenCalledWith(
        expect.objectContaining({ jobType: 'CSV_IMPORT' })
      )
    })
  })

  it('calls getJobs with page param when URL has page', async () => {
    renderJobList('page=2')
    await waitFor(() => {
      expect(mockGetJobs).toHaveBeenCalledWith(
        expect.objectContaining({ page: 2 })
      )
    })
  })

  it('shows pagination when totalPages > 1', async () => {
    mockGetJobs.mockResolvedValue({
      content: mockJobs,
      number: 0,
      size: 20,
      totalElements: 50,
      totalPages: 3,
    })
    renderJobList()
    await waitFor(() => {
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    })
  })

  it('shows status badges', async () => {
    renderJobList()
    await screen.findByText('CSV Import')
    expect(screen.getByText('QUEUED')).toBeInTheDocument()
    expect(screen.getByText('RUNNING')).toBeInTheDocument()
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
  })

  it('shows priority badges', async () => {
    renderJobList()
    await screen.findByText('CSV Import')
    expect(screen.getByText('HIGH')).toBeInTheDocument()
    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
    expect(screen.getByText('LOW')).toBeInTheDocument()
  })

  it('renders filter dropdowns', async () => {
    renderJobList()
    await screen.findByText('CSV Import')
    expect(screen.getByDisplayValue('All statuses')).toBeInTheDocument()
    expect(screen.getByDisplayValue('All types')).toBeInTheDocument()
  })
})
