import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { CreateJob } from '../CreateJob'

const mockCreateJob = vi.fn()

vi.mock('../../api/client', () => ({
  api: {
    createJob: (...args: unknown[]) => mockCreateJob(...args),
    isAuthenticated: () => true,
    getMe: () => Promise.resolve({ id: 'u1', email: 'test@test.com', role: 'USER', isActive: true, createdAt: '' }),
  },
}))

function renderCreateJob() {
  return render(
    <MemoryRouter>
      <CreateJob />
    </MemoryRouter>
  )
}

describe('CreateJob form validation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows error when no job type selected', async () => {
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('Submit job'))
    expect(screen.getByText('Please select a job type')).toBeInTheDocument()
    expect(mockCreateJob).not.toHaveBeenCalled()
  })

  it('shows error for invalid JSON payload', async () => {
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('CSV Import'))

    const payloadField = screen.getByLabelText(/payload/i)
    await user.clear(payloadField)
    fireEvent.change(payloadField, { target: { value: 'not valid json' } })

    await user.click(screen.getByText('Submit job'))
    expect(screen.getByText('Invalid JSON')).toBeInTheDocument()
    expect(mockCreateJob).not.toHaveBeenCalled()
  })

  it('shows error when cron expression is empty in cron mode', async () => {
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('CSV Import'))
    await user.click(screen.getByText('Recurring (cron)'))
    await user.click(screen.getByText('Submit job'))

    expect(screen.getByText('Please enter a cron expression')).toBeInTheDocument()
    expect(mockCreateJob).not.toHaveBeenCalled()
  })

  it('shows error when scheduled date is empty in future mode', async () => {
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('CSV Import'))
    await user.click(screen.getByText('Schedule for later'))
    await user.click(screen.getByText('Submit job'))

    expect(screen.getByText('Please select a date and time')).toBeInTheDocument()
    expect(mockCreateJob).not.toHaveBeenCalled()
  })

  it('submits successfully with valid payload', async () => {
    mockCreateJob.mockResolvedValue({ id: 'new-job-id', status: 'QUEUED' })
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('CSV Import'))

    const payloadField = screen.getByLabelText(/payload/i)
    await user.clear(payloadField)
    fireEvent.change(payloadField, { target: { value: '{"rows": 1000}' } })

    await user.click(screen.getByText('Submit job'))

    await waitFor(() => {
      expect(mockCreateJob).toHaveBeenCalledWith(
        expect.objectContaining({
          jobType: 'CSV_IMPORT',
          payload: '{"rows": 1000}',
        })
      )
    })
  })

  it('shows error when API rejects submission', async () => {
    mockCreateJob.mockRejectedValue({ error: { message: 'Job type not supported' } })
    const user = userEvent.setup()
    renderCreateJob()

    await user.click(screen.getByText('CSV Import'))

    const payloadField = screen.getByLabelText(/payload/i)
    await user.clear(payloadField)
    fireEvent.change(payloadField, { target: { value: '{}' } })

    await user.click(screen.getByText('Submit job'))

    expect(await screen.findByText('Job type not supported')).toBeInTheDocument()
  })

  it('validates payload in real time as user types', async () => {
    renderCreateJob()

    const payloadField = screen.getByLabelText(/payload/i)
    fireEvent.change(payloadField, { target: { value: '{' } })

    expect(screen.getByText('Invalid JSON')).toBeInTheDocument()

    fireEvent.change(payloadField, { target: { value: '{}' } })
    expect(screen.queryByText('Invalid JSON')).not.toBeInTheDocument()
  })
})
