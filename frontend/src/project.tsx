import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Braces, Database, FlaskConical, Play, Plus, ShieldCheck, Trash2, Upload } from 'lucide-react'
import { api, date, errorMessage, useResource, type Finding, type Migration, type Probe, type Project, type Run } from './api'
import { Badge, Empty, Heading, Notice, RunsTable } from './ui'

const sections = ['Migrations', 'Database', 'Compatibility', 'Test history', 'Settings'] as const

export function ProjectPage() {
  const { id } = useParams()
  return <ProjectWorkspace key={id} id={id!} />
}

function ProjectWorkspace({ id }: { id: string }) {
  const project = useResource<Project>('/projects/' + id)
  const [search, setSearch] = useSearchParams()
  const section = sections.find(item => item === search.get('tab')) ?? sections[0]
  if (project.loading && !project.data) return <Notice>Loading project…</Notice>
  if (!project.data) return <Notice error>{project.error}</Notice>
  return <><Link className="text-link back" to="/projects"><ArrowLeft size={15} /> Projects</Link>
    <Heading eyebrow={'POSTGRESQL ' + project.data.postgresVersion + ' / PROJECT #' + id} title={project.data.name} description={project.data.description || 'Configure and rehearse your schema changes.'} />
    {project.error && <Notice error>{project.error}</Notice>}
    <div className="tabs" aria-label="Project sections">{sections.map(item => <button className={section === item ? 'selected' : ''} aria-pressed={section === item} key={item} onClick={() => setSearch(item === sections[0] ? {} : { tab: item })}>{item}</button>)}</div>
    {section === 'Migrations' && <Migrations id={id} />}
    {section === 'Database' && <DatabasePanel id={id} />}
    {section === 'Compatibility' && <Queries id={id} />}
    {section === 'Test history' && <History id={id} />}
    {section === 'Settings' && <Settings project={project.data} reload={project.reload} />}
  </>
}

function Migrations({ id }: { id: string }) {
  const migrations = useResource<Migration[]>('/projects/' + id + '/migrations')
  const navigate = useNavigate()
  const [selected, setSelected] = useState<number>()
  const migration = migrations.data?.find(m => m.id === selected) ?? migrations.data?.[0]
  const [validation, setValidation] = useState<{ id: number; valid: boolean; error?: string; findings: Finding[] }>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  async function action(name: 'upload' | 'validate' | 'test', event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault()
    const form = event?.currentTarget
    setBusy(name)
    setError('')
    try {
      if (name === 'upload' && form) {
        const { data } = await api.post<Migration>('/projects/' + id + '/migrations/upload', new FormData(form))
        setSelected(data.id)
        migrations.reload()
        form.reset()
      } else if (migration && name === 'validate') {
        const { data } = await api.post('/migrations/' + migration.id + '/validate')
        setValidation({ ...data, id: migration.id })
      } else if (migration && name === 'test') {
        const { data } = await api.post<Run>('/migrations/' + migration.id + '/test')
        navigate('/reports/' + data.id)
      }
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy('') }
  }
  const currentValidation = validation?.id === migration?.id ? validation : undefined
  return <>{(error || migrations.error) && <Notice error>{error || migrations.error}</Notice>}
    <div className="split"><section className="panel section"><div className="section-heading"><div><p className="eyebrow">MIGRATION FILES</p><h2>SQL workspace</h2></div><Braces size={20} className="muted" /></div>
      <form className="upload form-stack" onSubmit={e => action('upload', e)}><label>SQL or Flyway file<input type="file" name="file" accept=".sql" required /></label><button className="button secondary" disabled={!!busy}><Upload size={16} /> {busy === 'upload' ? 'Uploading…' : 'Upload migration'}</button></form>
      {migrations.loading && <Notice>Loading migrations…</Notice>}
      {!migrations.loading && !migration && <Empty>Upload a UTF-8 SQL file up to 256 KiB.</Empty>}
      <div className="file-list">{migrations.data?.map(item => <button className={migration?.id === item.id ? 'active' : ''} key={item.id} onClick={() => setSelected(item.id)}><Braces size={16} /><span>{item.name}<small>{date(item.createdAt)}</small></span></button>)}</div>
    </section>
    <section className="panel section">{migration ? <><div className="section-heading"><div><p className="eyebrow">SQL PREVIEW</p><h2 className="wrap-anywhere">{migration.name}</h2></div></div>
      <pre className="code">{migration.sql}</pre>
      {currentValidation && <Notice error={!currentValidation.valid}>{currentValidation.valid
        ? 'SQL parsed and passed the rehearsal policy. Static findings: ' + currentValidation.findings.length
        : currentValidation.error}</Notice>}
      {currentValidation?.findings.map((f, i) => <div className="line-item" key={i}><Badge value={f.severity} /><span>{f.title}</span></div>)}
      <div className="actions"><button className="button secondary" disabled={!!busy} onClick={() => action('validate')}><ShieldCheck size={16} /> {busy === 'validate' ? 'Validating…' : 'Validate SQL'}</button><button className="button primary" disabled={!!busy} onClick={() => action('test')}><Play size={16} /> {busy === 'test' ? 'Queuing…' : 'Run rehearsal'}</button></div>
    </> : <Empty>Select a migration to preview and rehearse its SQL.</Empty>}</section></div></>
}

function DatabasePanel({ id }: { id: string }) {
  const baseline = useResource<{ host: string; port: number; databaseName: string; username: string }>('/projects/' + id + '/database')
  const [schema, setSchema] = useState<Record<string, string>>()
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const values = Object.fromEntries(new FormData(form))
    const button = (event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null
    const test = button?.value === 'test'
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const { data } = await api.post(test ? '/databases/test-connection' : '/projects/' + id + '/database',
        { ...values, port: Number(values.port) })
      setMessage(test ? 'Connected to PostgreSQL ' + data.version : 'Baseline configuration saved.')
      if (!test) (form.elements.namedItem('password') as HTMLInputElement).value = ''
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  async function inspect() {
    setBusy(true)
    setError('')
    try { setSchema((await api.get('/projects/' + id + '/schema')).data) }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <>{error && <Notice error>{error}</Notice>}{message && <Notice>{message}</Notice>}<div className="split">
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">BASELINE CONNECTION</p><h2>PostgreSQL database</h2></div><Database size={20} /></div>
      <p className="muted">Use the controlled <code>sr_fixture</code> with the <code>sr_reader</code> account for migration rehearsals.</p>
      {baseline.loading ? <Notice>Loading connection…</Notice> : baseline.error ? <Notice error>{baseline.error}</Notice> : <form className="form-stack" onSubmit={submit}>
        <div className="form-row"><label>Host<input name="host" defaultValue={baseline.data?.host} required /></label><label>Port<input name="port" type="number" min={1} max={65535} defaultValue={baseline.data?.port} required /></label></div>
        <label>Database name<input name="databaseName" defaultValue={baseline.data?.databaseName} required /></label>
        <label>Username<input name="username" defaultValue={baseline.data?.username} required /></label>
        <label>Password<input name="password" type="password" autoComplete="off" required maxLength={512} /></label>
        <div className="actions"><button className="button primary" value="save" disabled={busy}>Save baseline</button><button className="button secondary" value="test" disabled={busy}>Test connection</button></div>
      </form>}</section>
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">PUBLIC SCHEMA</p><h2>Schema inspection</h2></div><button className="button secondary" disabled={busy} onClick={inspect}>Inspect</button></div>
      {schema && Object.keys(schema).length ? <div className="schema-list">{Object.entries(schema).map(([name, definition]) => <div key={name}><code>{name}</code><span>{definition}</span></div>)}</div>
      : <Empty>{schema ? 'No supported public schema objects were found.' : 'Inspect the saved baseline to list tables, columns, constraints, and indexes.'}</Empty>}</section>
    </div></>
}

function Queries({ id }: { id: string }) {
  const queries = useResource<Probe[]>('/projects/' + id + '/queries')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    setBusy(true)
    setError('')
    try { await api.post('/projects/' + id + '/queries', Object.fromEntries(new FormData(form))); form.reset(); queries.reload() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  async function remove(query: Probe) {
    setBusy(true)
    setError('')
    try { await api.delete('/projects/' + id + '/queries/' + query.id); queries.reload() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <>{(error || queries.error) && <Notice error>{error || queries.error}</Notice>}<div className="split">
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">APPLICATION CONTRACT</p><h2>Register a query</h2></div><FlaskConical size={20} /></div>
      <p className="muted">Old application queries run before and after Flyway. New application queries run after Flyway.</p>
      <form className="form-stack" onSubmit={add}><label>Query name<input name="name" required maxLength={120} placeholder="Customer directory" /></label>
        <label>Application version<select name="generation"><option value="OLD">Old application</option><option value="NEW">New application</option></select></label>
        <label>Read-only SQL<textarea name="sql" required maxLength={8000} className="mono" placeholder="SELECT id, email FROM users;" rows={5} /></label>
        <div><button className="button primary" disabled={busy}><Plus size={16} /> Add query</button></div>
      </form></section>
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">SAVED PROBES</p><h2>Compatibility queries</h2></div></div>
      {queries.loading ? <Notice>Loading queries…</Notice> : !queries.data?.length ? <Empty>Register a query your application relies on.</Empty> : queries.data.map(query => <article className="probe" key={query.id}>
        <div className="section-heading"><h3>{query.name} <Badge value={query.generation} /></h3><button className="icon-button" disabled={busy} aria-label={'Remove ' + query.name} onClick={() => remove(query)}><Trash2 size={16} /></button></div><pre className="code">{query.sql}</pre></article>)}
    </section></div></>
}

function History({ id }: { id: string }) {
  const runs = useResource<Run[]>('/projects/' + id + '/tests')
  return <section className="panel section"><div className="section-heading"><h2>Project rehearsals</h2><button className="button secondary" onClick={runs.reload}>Refresh</button></div>
    {runs.error && <Notice error>{runs.error}</Notice>}{runs.loading ? <Notice>Loading history…</Notice> : <RunsTable runs={runs.data ?? []} />}</section>
}

function Settings({ project, reload }: { project: Project; reload: () => void }) {
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = Object.fromEntries(new FormData(event.currentTarget))
    setBusy(true)
    setError('')
    setMessage('')
    try { await api.put('/projects/' + project.id, { ...data, postgresVersion: '18' }); reload(); setMessage('Project updated.') }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  async function remove() {
    setBusy(true)
    setError('')
    try { await api.delete('/projects/' + project.id); navigate('/projects') }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <div className="panel section">{error && <Notice error>{error}</Notice>}{message && <Notice>{message}</Notice>}
    <h2>Project settings</h2><p className="muted">Created {date(project.createdAt)}</p>
    <form className="form-stack" onSubmit={save}><label>Name<input name="name" defaultValue={project.name} required maxLength={120} /></label><label>Description<textarea name="description" defaultValue={project.description} maxLength={2000} /></label><div><button className="button primary" disabled={busy}>Save changes</button></div></form>
    <div className="danger-zone"><h3>Delete project</h3><p className="muted">Permanently remove this project and its migrations, probes, and reports.</p>
      {confirm ? <div className="actions"><button className="button danger" onClick={remove} disabled={busy}>Confirm deletion</button><button className="button secondary" onClick={() => setConfirm(false)}>Cancel</button></div>
        : <button className="button danger" onClick={() => setConfirm(true)}><Trash2 size={16} /> Delete project</button>}</div>
  </div>
}
