import { lazy, Suspense, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Activity, ArrowRight, CheckCircle2, Database, FlaskConical, FolderKanban, Plus, ShieldAlert, XCircle } from 'lucide-react'
import { api, date, errorMessage, pending, risk, useResource, type Project, type Run } from './api'
import { Empty, Heading, Notice, RunsTable } from './ui'

const RiskChart = lazy(() => import('./risk-chart'))

export function Dashboard() {
  const projects = useResource<Project[]>('/projects')
  const runs = useResource<Run[]>('/reports')
  const items = runs.data ?? []
  const completed = items.filter(run => !pending(run))
  const chart = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((name, i) => ({
    name, value: completed.filter(r => risk(r.score) === name).length,
    fill: ['#168367', '#db9d43', '#e36e4d', '#a84e73'][i],
  }))
  const cards = [
    { label: 'Projects', value: projects.data?.length, icon: FolderKanban, tone: 'mint' },
    { label: 'Total rehearsals', value: runs.data?.length, icon: FlaskConical, tone: 'blue' },
    { label: 'Successful', value: items.filter(r => r.status === 'SUCCEEDED').length, icon: CheckCircle2, tone: 'mint' },
    { label: 'Failed', value: items.filter(r => r.status === 'FAILED').length, icon: XCircle, tone: 'red' },
    { label: 'High or critical', value: completed.filter(r => r.score > 40).length, icon: ShieldAlert, tone: 'amber' },
  ]
  return <><Heading eyebrow="WORKSPACE OVERVIEW" title="Migration overview" description="Track execution, compatibility, and risk across your PostgreSQL projects." action={<Link className="button primary" to="/projects?new=1"><Plus size={17} /> New project</Link>} />
    {(projects.error || runs.error) && <Notice error>{projects.error || runs.error}</Notice>}
    {(projects.loading || runs.loading) && <Notice>Loading workspace metrics…</Notice>}
    <section className="metric-grid" aria-label="Workspace metrics">{cards.map(({ label, value, icon: Icon, tone }) => <article className="panel metric" key={label}>
      <div className={'tile-icon ' + tone}><Icon size={19} /></div><span>{label}</span><strong>{projects.loading || runs.loading || projects.error || runs.error ? '—' : value ?? '—'}</strong>
    </article>)}</section>
    <div className="dashboard-grid"><section className="panel section"><div className="section-heading"><div><p className="eyebrow">EXECUTION HISTORY</p><h2>Recent rehearsals</h2></div><Link className="text-link" to="/reports">View reports <ArrowRight size={14} /></Link></div><RunsTable runs={items.slice(0, 5)} /></section>
      <section className="panel section risk-chart"><div className="section-heading"><div><p className="eyebrow">COMPLETED RUNS</p><h2>Risk distribution</h2></div><Activity size={18} className="muted" /></div>
        {completed.length ? <Suspense fallback={<Notice>Loading chart…</Notice>}><RiskChart chart={chart} total={completed.length} /></Suspense> : <Empty>Risk distribution appears after the first completed run.</Empty>}
        <div className="legend">{chart.map(c => <span key={c.name}><i style={{ background: c.fill }} /> {c.name.toLowerCase()} <b>{c.value}</b></span>)}</div>
      </section></div>
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">BASELINES & MIGRATIONS</p><h2>Recent projects</h2></div><Link className="text-link" to="/projects">All projects <ArrowRight size={14} /></Link></div>
      {projects.data?.length ? <div className="project-grid">{projects.data.slice(0, 3).map(project => <ProjectCard project={project} key={project.id} />)}</div> : !projects.loading && <Empty>Create a project or load the demo to start rehearsing.</Empty>}
    </section></>
}

function ProjectCard({ project }: { project: Project }) {
  return <Link className="project-card" to={'/projects/' + project.id}><div className="tile-icon"><Database size={19} /></div><h3>{project.name}</h3><p>{project.description || 'PostgreSQL migration project'}</p><footer><span>PostgreSQL {project.postgresVersion}</span><ArrowRight size={16} /></footer></Link>
}

export function ProjectsPage() {
  const projects = useResource<Project[]>('/projects')
  const navigate = useNavigate()
  const [show, setShow] = useState(new URLSearchParams(location.search).has('new'))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function create(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault()
    const body = event ? { ...Object.fromEntries(new FormData(event.currentTarget)), postgresVersion: '18' } : undefined
    setBusy(true)
    setError('')
    try {
      const response = await api.post<Project>(body ? '/projects' : '/projects/demo', body)
      navigate('/projects/' + response.data.id)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <><Heading eyebrow="MIGRATION WORKSPACE" title="Projects" description="Organize baselines, SQL migrations, compatibility probes, and reports." action={<div className="actions"><button className="button secondary" disabled={busy} onClick={() => void create()}><FlaskConical size={16} /> Load demo</button><button className="button primary" onClick={() => setShow(!show)}><Plus size={17} /> New project</button></div>} />
    {(error || projects.error) && <Notice error>{error || projects.error}</Notice>}
    {show && <form className="panel section form-stack" onSubmit={create}><h2>Create project</h2><label>Project name<input name="name" required maxLength={120} placeholder="Commerce API" /></label><label>Description<textarea name="description" maxLength={2000} placeholder="What schema are you rehearsing?" /></label><div><button className="button primary" disabled={busy}>Create project</button></div></form>}
    {projects.loading ? <Notice>Loading projects…</Notice> : projects.data?.length ? <div className="project-grid">{projects.data.map(project => <ProjectCard project={project} key={project.id} />)}</div> : <div className="panel section"><Empty>Start a project or load the commerce demo with five sample migrations.</Empty></div>}
  </>
}

export function Reports() {
  const runs = useResource<Run[]>('/reports')
  const [filter, setFilter] = useState('')
  const visible = (runs.data ?? []).filter(run => !filter || run.status === filter)
  return <><Heading eyebrow="EVIDENCE LIBRARY" title="Rehearsal reports" description="Review execution results, risk factors, and downloadable evidence." action={<button className="button secondary" onClick={runs.reload}>Refresh</button>} />
    {runs.error && <Notice error>{runs.error}</Notice>}
    <section className="panel section"><div className="section-heading"><div><h2>Run history</h2><p className="muted">{runs.data?.length ? 'Latest run started ' + date(runs.data[0].createdAt) : 'Reports are generated when rehearsals run.'}</p></div>
      <label className="compact-label">Status<select value={filter} onChange={e => setFilter(e.target.value)}><option value="">All statuses</option><option>SUCCEEDED</option><option>FAILED</option><option>QUEUED</option><option>RUNNING</option></select></label></div>
      {runs.loading ? <Notice>Loading reports…</Notice> : <RunsTable runs={visible} />}
    </section></>
}
