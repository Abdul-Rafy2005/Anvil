import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AdminRoute } from '../../../components/AdminRoute'

const mockGetMe = vi.fn()
let mockUser: { id: string; email: string; role: string; isActive: boolean; createdAt: string } | null = null
let mockLoading = false

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({
    user: mockUser,
    loading: mockLoading,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
  }),
  AuthProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('../../../api/client', () => ({
  api: {
    getMe: (...args: unknown[]) => mockGetMe(...args),
    isAuthenticated: () => true,
  },
}))

describe('AdminRoute', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockLoading = false
  })

  it('redirects to /login when not authenticated', () => {
    mockUser = null
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminRoute><div>Admin content</div></AdminRoute>
      </MemoryRouter>
    )
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument()
  })

  it('redirects to /jobs when user is not ADMIN', () => {
    mockUser = { id: 'u1', email: 'user@test.com', role: 'USER', isActive: true, createdAt: '' }
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminRoute><div>Admin content</div></AdminRoute>
      </MemoryRouter>
    )
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument()
  })

  it('renders admin content when user is ADMIN', () => {
    mockUser = { id: 'u1', email: 'admin@test.com', role: 'ADMIN', isActive: true, createdAt: '' }
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminRoute><div>Admin content</div></AdminRoute>
      </MemoryRouter>
    )
    expect(screen.getByText('Admin content')).toBeInTheDocument()
  })

  it('shows spinner while loading', () => {
    mockLoading = true
    mockUser = null
    const { container } = render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminRoute><div>Admin content</div></AdminRoute>
      </MemoryRouter>
    )
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
  })
})
