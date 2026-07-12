import { useEffect, useRef, useCallback, useState } from 'react'
import { api } from '../api/client'

interface WsMessage {
  type: string
  jobId?: string
  status?: string
  progressPct?: number
  message?: string
}

export function useJobWebSocket(jobId: string | null) {
  const [progress, setProgress] = useState<number>(0)
  const [status, setStatus] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const connect = useCallback(() => {
    const token = api.getAccessToken()
    if (!jobId || !token) return

    const url = `${window.location.protocol}//${window.location.host}/ws`

    const sockJsScript = document.querySelector('script[src*="sockjs"]')
    if (!sockJsScript) {
      const s = document.createElement('script')
      s.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js'
      s.onload = () => connectWithSockJS(url, token, jobId)
      document.head.appendChild(s)
    } else {
      connectWithSockJS(url, token, jobId)
    }
  }, [jobId])

  function connectWithSockJS(url: string, token: string, jId: string) {
    if (wsRef.current) {
      wsRef.current.close()
    }

    const SockJS = (window as unknown as Record<string, unknown>).SockJS as
      | ((url: string) => WebSocket)
      | undefined
    if (!SockJS) {
      fallbackConnect(url, token, jId)
      return
    }

    const sock = new (SockJS as unknown as new (url: string) => WebSocket)(url)
    wsRef.current = sock

    const stompConnect = () => {
      const frame = `CONNECT\nAuthorization:Bearer ${token}\naccept-version:1.1,1.0\nheart-beat:10000,10000\n\n\x00`
      sock.send(frame)
    }

    let connected = false
    sock.onopen = () => stompConnect()

    sock.onmessage = (event: MessageEvent) => {
      const data = typeof event.data === 'string' ? event.data : ''

      if (data.startsWith('CONNECTED')) {
        connected = true
        const subFrame = `SUBSCRIBE\nid:sub-${jId}\ndestination:/topic/job/${jId}\n\n\x00`
        sock.send(subFrame)
        return
      }

      if (data.includes('"type"')) {
        try {
          const jsonMatch = data.match(/\{[\s\S]*\}/)
          if (jsonMatch) {
            const msg: WsMessage = JSON.parse(jsonMatch[0])
            if (msg.type === 'PROGRESS' && msg.progressPct !== undefined) {
              setProgress(msg.progressPct)
              setMessage(msg.message ?? null)
            }
            if (msg.type === 'STATUS' && msg.status) {
              setStatus(msg.status)
            }
          }
        } catch {
          // ignore parse errors
        }
      }
    }

    sock.onclose = () => {
      if (connected) {
        reconnectTimer.current = setTimeout(connect, 3000)
      }
    }

    sock.onerror = () => {
      sock.close()
    }
  }

  function fallbackConnect(url: string, token: string, jId: string) {
    const wsUrl = url.replace(/^ws/, 'http').replace('/ws', '/ws/info')
    void wsUrl
    void token
    void jId
    fallbackPoll(jId)
  }

  function fallbackPoll(jId: string) {
    const poll = async () => {
      try {
        const job = await api.getJob(jId)
        setProgress(job.progressPct ?? 0)
        setStatus(job.status)
        setMessage(job.progressMessage)
      } catch {
        // ignore
      }
    }
    const interval = setInterval(poll, 2000)
    return () => clearInterval(interval)
  }

  useEffect(() => {
    connect()
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
      if (wsRef.current) wsRef.current.close()
    }
  }, [connect])

  return { progress, status, message }
}
