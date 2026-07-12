import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useJobWebSocket } from '../useWebSocket'

const mockGetAccessToken = vi.fn()
const mockGetJob = vi.fn()

vi.mock('../../api/client', () => ({
  api: {
    getAccessToken: () => mockGetAccessToken(),
    getJob: (...args: unknown[]) => mockGetJob(...args),
  },
}))

describe('useJobWebSocket', () => {
  let originalSockJS: unknown
  let fakeScript: HTMLScriptElement | null = null

  beforeEach(() => {
    vi.clearAllMocks()
    mockGetAccessToken.mockReturnValue('fake-token')
    originalSockJS = (window as unknown as Record<string, unknown>).SockJS

    fakeScript = document.createElement('script')
    fakeScript.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js'
    document.head.appendChild(fakeScript)
  })

  afterEach(() => {
    if (fakeScript && fakeScript.parentNode) {
      fakeScript.parentNode.removeChild(fakeScript)
    }
    if (originalSockJS !== undefined) {
      ;(window as unknown as Record<string, unknown>).SockJS = originalSockJS
    } else {
      delete (window as unknown as Record<string, unknown>).SockJS
    }
  })

  it('constructs SockJS URL with http:// scheme, not ws://', () => {
    let capturedUrl: string | undefined
    const FakeSockJS = vi.fn(function (this: Record<string, unknown>, url: string) {
      capturedUrl = url
      this.send = vi.fn()
      this.close = vi.fn()
      this.onopen = null
      this.onmessage = null
      this.onclose = null
      this.onerror = null
    })
    ;(window as unknown as Record<string, unknown>).SockJS = FakeSockJS

    renderHook(() => useJobWebSocket('test-job-id'))

    expect(capturedUrl).toBeDefined()
    expect(capturedUrl).toMatch(/^https?:\/\//)
    expect(capturedUrl).not.toMatch(/^ws:\/\//)
    expect(capturedUrl).not.toMatch(/^wss:\/\//)
  })

  it('does not connect when jobId is null', () => {
    const FakeSockJS = vi.fn()
    ;(window as unknown as Record<string, unknown>).SockJS = FakeSockJS

    renderHook(() => useJobWebSocket(null))

    expect(FakeSockJS).not.toHaveBeenCalled()
  })

  it('uses window.location as the URL base', () => {
    let capturedUrl: string | undefined
    const FakeSockJS = vi.fn(function (this: Record<string, unknown>, url: string) {
      capturedUrl = url
      this.send = vi.fn()
      this.close = vi.fn()
      this.onopen = null
      this.onmessage = null
      this.onclose = null
      this.onerror = null
    })
    ;(window as unknown as Record<string, unknown>).SockJS = FakeSockJS

    renderHook(() => useJobWebSocket('test-job-id'))

    expect(capturedUrl).toContain(window.location.host)
    expect(capturedUrl).toContain('/ws')
  })
})
