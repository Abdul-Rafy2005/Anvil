import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { NotificationCenter } from './NotificationCenter'
import { useState } from 'react'

export function Layout({ children }: { children: React.ReactNode }) {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 flex flex-col">
      <header className="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur-sm sticky top-0 z-40">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-6">
            <Link to="/" className="flex items-center gap-2">
              <div className="w-6 h-6 bg-white rounded flex items-center justify-center">
                <span className="text-zinc-950 text-xs font-bold">A</span>
              </div>
              <span className="text-sm font-semibold text-zinc-100">Anvil</span>
            </Link>
            <nav className="flex items-center gap-1">
              <NavLink to="/jobs" current={location.pathname}>Jobs</NavLink>
              <NavLink to="/jobs/new" current={location.pathname}>New Job</NavLink>
              {user?.role === 'ADMIN' && (
                <NavLink to="/admin" current={location.pathname}>Admin</NavLink>
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <NotificationCenter />
            <div className="relative">
              <button
                onClick={() => setMenuOpen(!menuOpen)}
                className="flex items-center gap-2 text-sm text-zinc-400 hover:text-zinc-200 transition-colors"
              >
                <div className="w-7 h-7 rounded-full bg-zinc-800 flex items-center justify-center text-xs font-medium text-zinc-300">
                  {user?.email?.[0]?.toUpperCase() ?? '?'}
                </div>
              </button>
              {menuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} />
                  <div className="absolute right-0 top-full mt-1 w-56 bg-zinc-900 border border-zinc-800 rounded-lg shadow-xl py-1 z-50">
                    <div className="px-3 py-2 border-b border-zinc-800">
                      <p className="text-sm text-zinc-300 truncate">{user?.email}</p>
                      <p className="text-xs text-zinc-500">{user?.role}</p>
                    </div>
                    <button
                      onClick={handleLogout}
                      className="w-full text-left px-3 py-2 text-sm text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 transition-colors"
                    >
                      Sign out
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      </header>
      <main className="flex-1 max-w-6xl mx-auto w-full px-4 py-6">{children}</main>
    </div>
  )
}

function NavLink({ to, current, children }: { to: string; current: string; children: React.ReactNode }) {
  const active = current === to || current.startsWith(to + '/')
  return (
    <Link
      to={to}
      className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
        active
          ? 'bg-zinc-800 text-zinc-100'
          : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800/50'
      }`}
    >
      {children}
    </Link>
  )
}
