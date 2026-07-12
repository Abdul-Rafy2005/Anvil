import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { JobDetail } from '../JobDetail'

const mockGetJob = vi.fn()
const mockGetAccessToken = vi.fn()

vi.mock('../../api/client', () => ({
  api: {
    getJob: (...args: unknown[]) => mockGetJob(...args),
    getAccessToken: () => mockGetAccessToken(),
    cancelJob: vi.fn(),
  },
}))

vi.mock('../../hooks/useWebSocket', () => ({
  useJobWebSocket: () => ({ progress: 0, status: null, message: null }),
}))

const BASE_JOB = {
  id: '79a5e7d2-7c7d-4b01-bc0a-def30c01c547',
  status: 'COMPLETED',
  priority: 'HIGH',
  attemptCount: 1,
  maxRetries: 3,
  progressPct: 100,
  progressMessage: 'Done',
  errorMessage: null,
  payload: '{}',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:01:00Z',
  startedAt: '2026-01-01T00:00:01Z',
  completedAt: '2026-01-01T00:01:00Z',
  scheduledAt: null,
  cronExpression: null,
  nextFireAt: null,
}

function renderDetail(job: Record<string, unknown>) {
  return render(
    <MemoryRouter initialEntries={[`/jobs/${job.id}`]}>
      <Routes>
        <Route path="/jobs/:id" element={<JobDetail />} />
      </Routes>
    </MemoryRouter>
  )
}

describe('JobDetail result rendering', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetAccessToken.mockReturnValue('fake-token')
  })

  it('shows generated text for AI_CONTENT_GENERATION', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'AI_CONTENT_GENERATION',
      result: '{"generatedText":"Hello world content","tokenCount":150,"model":"anvil-llm-v1"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'AI_CONTENT_GENERATION' })
    expect(await screen.findByText('Hello world content')).toBeInTheDocument()
    expect(screen.getByText('anvil-llm-v1')).toBeInTheDocument()
    expect(screen.getByText('150')).toBeInTheDocument()
  })

  it('shows row counts for CSV_IMPORT', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'CSV_IMPORT',
      result: '{"rowsProcessed":100000,"errors":0,"duration":"completed"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'CSV_IMPORT' })
    expect(await screen.findByText('Rows processed')).toBeInTheDocument()
    expect(screen.getByText('100000')).toBeInTheDocument()
    expect(screen.getByText('Errors')).toBeInTheDocument()
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('shows recipient counts for EMAIL_CAMPAIGN', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'EMAIL_CAMPAIGN',
      result: '{"sent":1000,"bounced":0,"campaignId":"abc-123"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'EMAIL_CAMPAIGN' })
    expect(await screen.findByText('Sent')).toBeInTheDocument()
    expect(screen.getByText('1000')).toBeInTheDocument()
    expect(screen.getByText('Bounced')).toBeInTheDocument()
  })

  it('shows compression summary with disabled download for FILE_COMPRESSION', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'FILE_COMPRESSION',
      result: '{"archiveUrl":"/archives/job.zip","originalSize":"250MB","compressedSize":"85MB","ratio":"66%"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'FILE_COMPRESSION' })
    expect(await screen.findByText('Original')).toBeInTheDocument()
    expect(screen.getByText('250MB')).toBeInTheDocument()
    expect(screen.getByText('85MB')).toBeInTheDocument()
    const btn = screen.getByRole('button', { name: /simulated download/i })
    expect(btn).toBeDisabled()
  })

  it('shows image conversion summary for IMAGE_PROCESSING', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'IMAGE_PROCESSING',
      result: '{"convertedCount":50,"outputFormat":"WebP","outputDir":"/output/job"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'IMAGE_PROCESSING' })
    expect(await screen.findByText('Images converted')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
    expect(screen.getByText('WebP')).toBeInTheDocument()
  })

  it('shows report summary with disabled download for REPORT_GENERATION', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'REPORT_GENERATION',
      result: '{"reportUrl":"/reports/job.pdf","pages":42,"format":"PDF"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'REPORT_GENERATION' })
    expect(await screen.findByText('Format')).toBeInTheDocument()
    expect(screen.getByText('PDF')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    const btn = screen.getByRole('button', { name: /simulated download/i })
    expect(btn).toBeDisabled()
  })

  it('falls back to raw JSON for unknown job types', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'UNKNOWN_TYPE',
      result: '{"custom":"data"}',
    })
    renderDetail({ ...BASE_JOB, jobType: 'UNKNOWN_TYPE' })
    await waitFor(() => {
      expect(screen.queryByText('Loading')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Result')).toBeInTheDocument()
    expect(screen.getByText('{"custom":"data"}')).toBeInTheDocument()
  })

  it('falls back to raw JSON when result is not valid JSON', async () => {
    mockGetJob.mockResolvedValue({
      ...BASE_JOB,
      jobType: 'CSV_IMPORT',
      result: 'plain text result',
    })
    renderDetail({ ...BASE_JOB, jobType: 'CSV_IMPORT' })
    await waitFor(() => {
      expect(screen.queryByText('Loading')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Result')).toBeInTheDocument()
    expect(screen.getByText('plain text result')).toBeInTheDocument()
  })
})
