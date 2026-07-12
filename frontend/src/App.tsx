import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { Layout } from './components/Layout'
import { AdminRoute } from './components/AdminRoute'
import { Login } from './pages/Login'
import { Register } from './pages/Register'
import { JobList } from './pages/JobList'
import { CreateJob } from './pages/CreateJob'
import { JobDetail } from './pages/JobDetail'
import { AdminOverview } from './pages/admin/Overview'
import { Workers } from './pages/admin/Workers'
import { Dlq } from './pages/admin/Dlq'
import { AuditLog } from './pages/admin/AuditLog'
import type { ReactNode } from 'react'

function ProtectedRoute({ children }: { children: ReactNode }) {
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
  return <Layout>{children}</Layout>
}

function PublicRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return null
  if (user) return <Navigate to="/jobs" replace />
  return <>{children}</>
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<PublicRoute><Login /></PublicRoute>} />
      <Route path="/register" element={<PublicRoute><Register /></PublicRoute>} />
      <Route path="/jobs" element={<ProtectedRoute><JobList /></ProtectedRoute>} />
      <Route path="/jobs/new" element={<ProtectedRoute><CreateJob /></ProtectedRoute>} />
      <Route path="/jobs/:id" element={<ProtectedRoute><JobDetail /></ProtectedRoute>} />
      <Route path="/admin" element={<AdminRoute><AdminOverview /></AdminRoute>} />
      <Route path="/admin/workers" element={<AdminRoute><Workers /></AdminRoute>} />
      <Route path="/admin/dlq" element={<AdminRoute><Dlq /></AdminRoute>} />
      <Route path="/admin/audit" element={<AdminRoute><AuditLog /></AdminRoute>} />
      <Route path="/" element={<Navigate to="/jobs" replace />} />
      <Route path="*" element={<Navigate to="/jobs" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  )
}
