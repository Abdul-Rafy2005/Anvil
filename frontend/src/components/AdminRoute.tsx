import { Navigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { Layout } from './Layout'
import type { ReactNode } from 'react'

export function AdminRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) {
    return (
      <Layout>
        <div className="flex items-center justify-center py-20">
          <div className="w-5 h-5 border-2 border-zinc-700 border-t-zinc-300 rounded-full animate-spin" />
        </div>
      </Layout>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN') return <Navigate to="/jobs" replace />
  return <Layout>{children}</Layout>
}
