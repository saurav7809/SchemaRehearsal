import { Component, StrictMode, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, NavLink, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { Activity, ArrowRight, BookOpen, Database, FolderKanban, LayoutDashboard, LogOut, ShieldCheck, Terminal, Workflow } from 'lucide-react'
import { api, errorMessage, sessionExpiredEvent, useResource } from './api'
import { Brand, Notice } from './ui'
import { Dashboard, ProjectsPage, Reports } from './overview'
import { ProjectPage } from './project'
import { ReportPage } from './report'
import './style.css'

function Landing() {
  return <div className="landing"><header className="marketing-nav"><Link to="/"><Brand /></Link><nav><a href="#workflow">Workflow</a><Link to="/login">Sign in</Link><Link className="button primary" to="/register">Get started <ArrowRight size={15} /></Link></nav></header>
    <main><section className="hero"><div><p className="eyebrow"><span className="pulse" /> POSTGRESQL MIGRATION WORKSPACE</p>
      <h1>Rehearse your migrations.<br /><em>Ship with evidence.</em></h1>
      <p>Test database changes in an isolated PostgreSQL fixture. Catch execution errors, see what changed, and find out which application queries still work.</p>
      <div className="actions"><Link className="button primary" to="/register">Get started <ArrowRight size={17} /></Link><a className="button secondary" href="#workflow">Explore the workflow</a></div>
      <div className="hero-meta"><ShieldCheck size={15} /> Local execution <span>·</span> PostgreSQL 18 <span>·</span> Flyway</div>
    </div><div className="terminal"><div className="terminal-bar"><span /><span /><span /><small>rehearsal / example</small></div><div className="terminal-body">
      <div className="term-command"><span>$</span> rehearse drop_email.sql</div>
      <p><b>01</b> Baseline snapshot <strong>READY</strong></p>
      <p><b>02</b> Isolated fixture <strong>READY</strong></p>
      <p><b>03</b> Flyway migration <strong>APPLIED</strong></p>
      <p><b>04</b> Legacy customer query <strong className="term-warn">REGRESSION</strong></p>
      <div className="term-result"><Activity size={17} /> Risk evidence captured <small>Example output</small></div>
    </div></div></section>
    <section className="landing-workflow" id="workflow"><p className="eyebrow">THE REHEARSAL WORKFLOW</p><h2>From migration file to release evidence.</h2><div className="feature-grid">
      {[[Database, '01', 'Clone a test fixture', 'Run Flyway on a fresh copy of your controlled PostgreSQL baseline.'], [Workflow, '02', 'Check compatibility', 'Compare schema changes and test old and new application queries.'], [BookOpen, '03', 'Review the evidence', 'Inspect SQLSTATE errors, score factors, and recommendations in a downloadable report.']].map(([Icon, number, title, description]) => {
        const FeatureIcon = Icon as typeof Database
        return <article className="panel feature" key={String(number)}><FeatureIcon size={22} /><small>{String(number)}</small><h3>{String(title)}</h3><p>{String(description)}</p></article>
      })}
    </div></section></main><footer className="marketing-footer"><Brand /><span>Safe testing for database changes</span></footer></div>
}

function Auth({ register = false }: { register?: boolean }) {
  const navigate = useNavigate()
  const { state } = useLocation()
  const returnTo = state?.returnTo
  const next = typeof returnTo === 'string' && /^\/(dashboard|projects|reports)([/?#]|$)/.test(returnTo)
    && !returnTo.includes('\\') ? returnTo : '/dashboard'
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = Object.fromEntries(new FormData(event.currentTarget))
    setBusy(true)
    setError('')
    try {
      if (register) await api.post('/auth/register', data)
      await api.post('/auth/login', data)
      navigate(next, { replace: true })
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <main className="auth-shell"><Link to="/"><Brand /></Link><section className="panel auth-card">
    <div className="tile-icon"><Terminal size={21} /></div><p className="eyebrow">DEVELOPER WORKSPACE</p>
    <h1>{register ? 'Create your account' : 'Welcome back'}</h1><p className="muted">{register ? 'Start your first migration rehearsal.' : 'Sign in to your rehearsal workspace.'}</p>
    {state?.sessionExpired && <Notice>Your session expired. Sign in to return to your workspace.</Notice>}
    <form onSubmit={submit} className="form-stack" aria-busy={busy}>
      <label>Email address<input name="email" type="email" required maxLength={254} placeholder="you@example.com" autoComplete="email" /></label>
      <label>Password<input name="password" type="password" minLength={8} maxLength={72} required autoComplete={register ? 'new-password' : 'current-password'} placeholder="At least 8 characters" /></label>
      {error && <Notice error>{error}</Notice>}
      <button className="button primary" disabled={busy}>{busy ? 'Please wait…' : register ? 'Create account' : 'Sign in'} <ArrowRight size={16} /></button>
    </form>
    <p className="muted auth-switch">{register ? 'Already have an account?' : 'New to SchemaRehearsal?'} <Link to={register ? '/login' : '/register'} state={state}>{register ? 'Sign in' : 'Create an account'}</Link></p>
  </section></main>
}

function Shell() {
  const session = useResource<{ email: string }>('/auth/me')
  const { pathname, search, hash } = useLocation()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [expired, setExpired] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  useEffect(() => {
    const invalidate = () => setExpired(true)
    window.addEventListener(sessionExpiredEvent, invalidate)
    return () => window.removeEventListener(sessionExpiredEvent, invalidate)
  }, [])
  if (expired || session.status === 401) return <Navigate to="/login" replace state={{ returnTo: pathname + search + hash, sessionExpired: !!session.data }} />
  if (session.loading) return <main className="center"><Notice>Loading workspace…</Notice></main>
  if (!session.data) return <main className="center"><Notice error>{session.error}</Notice><button className="button secondary" onClick={session.reload}>Retry</button></main>
  return <div className="app-shell"><a className="skip-link" href="#workspace-main">Skip to workspace</a><aside className="sidebar"><Link to="/dashboard"><Brand /></Link>
    <p className="side-label">WORKSPACE</p><nav aria-label="Workspace">
      <NavLink to="/dashboard"><LayoutDashboard size={18} /> Overview</NavLink>
      <NavLink to="/projects"><FolderKanban size={18} /> Projects</NavLink>
      <NavLink to="/reports"><BookOpen size={18} /> Reports</NavLink>
    </nav><div className="side-footer"><div className="cluster"><span className="pulse" /><div>Local workspace<small>PostgreSQL 18</small></div></div>
      <button className="logout" disabled={signingOut} onClick={async () => {
        setSigningOut(true)
        setError('')
        try { await api.post('/auth/logout'); navigate('/login', { replace: true }) }
        catch (e) { setError(errorMessage(e)) }
        finally { setSigningOut(false) }
      }}><LogOut size={16} /> Sign out</button></div>
    </aside><div className="main-shell"><div className="topbar"><span>SchemaRehearsal <span className="muted">/ Workspace</span></span><div className="account"><span className="avatar">{session.data.email.slice(0, 1).toUpperCase()}</span><span>{session.data.email}</span></div></div>
    <main className="workspace" id="workspace-main" tabIndex={-1}>{error && <Notice error>{error}</Notice>}<Outlet /></main></div></div>
}

class WorkspaceBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    return this.state.failed ? <main className="center">
      <Notice error>The workspace could not be displayed.</Notice>
      <button className="button secondary" onClick={() => window.location.reload()}>Reload workspace</button>
    </main> : this.props.children
  }
}

createRoot(document.getElementById('root')!).render(<StrictMode><WorkspaceBoundary><BrowserRouter><Routes>
  <Route path="/" element={<Landing />} />
  <Route path="/login" element={<Auth key="login" />} />
  <Route path="/register" element={<Auth key="register" register />} />
  <Route element={<Shell />}>
    <Route path="/dashboard" element={<Dashboard />} />
    <Route path="/projects" element={<ProjectsPage />} />
    <Route path="/projects/:id" element={<ProjectPage />} />
    <Route path="/reports" element={<Reports />} />
    <Route path="/reports/:id" element={<ReportPage />} />
  </Route>
  <Route path="*" element={<Navigate to="/" replace />} />
</Routes></BrowserRouter></WorkspaceBoundary></StrictMode>)
