import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowUpRight, Database, FlaskConical } from 'lucide-react'
import { date, pending, risk, type Run } from './api'

export function Brand() {
  return <span className="brand"><span className="brand-mark"><Database size={19} /></span><span>Schema<span>Rehearsal</span></span></span>
}

export function Badge({ value }: { value: string }) {
  const tone = ['SUCCEEDED', 'APPLIED', 'LOW', 'PASS', 'ADDED'].includes(value) ? 'good'
    : ['FAILED', 'FAIL', 'HIGH', 'CRITICAL', 'REGRESSION', 'NEW_QUERY_FAILED', 'REMOVED'].includes(value) ? 'bad'
    : ['MEDIUM', 'RUNNING', 'SHAPE_CHANGED', 'MODIFIED'].includes(value) ? 'warn' : 'neutral'
  return <span className={'badge ' + tone}>{value.replaceAll('_', ' ')}</span>
}

export function Heading({ eyebrow, title, description, action }: { eyebrow: string; title: string; description?: string; action?: ReactNode }) {
  return <header className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1>{description && <p className="muted">{description}</p>}</div>{action}</header>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty"><FlaskConical size={23} /><p>{children}</p></div>
}

export function Notice({ error, children }: { error?: boolean; children: ReactNode }) {
  return <div role={error ? 'alert' : 'status'} className={'notice ' + (error ? 'error' : '')}>{children}</div>
}

export function RunsTable({ runs }: { runs: Run[] }) {
  if (!runs.length) return <Empty>Run a migration to see its execution and risk evidence here.</Empty>
  return <div className="table-wrap"><table><thead><tr><th>Migration</th><th>Status</th><th>Risk</th><th>Duration</th><th>Started</th><th><span className="sr-only">Report</span></th></tr></thead>
    <tbody>{runs.map(run => <tr key={run.id}><td><Link className="table-link" to={'/reports/' + run.id}>{run.migrationName}</Link><small>Run #{run.id}</small></td>
      <td><Badge value={run.status} /></td><td>{pending(run) ? '—' : <><Badge value={risk(run.score)} /> <span className="tabular">{run.score}</span></>}</td>
      <td>{run.durationMs} ms</td><td>{date(run.createdAt)}</td><td><Link className="icon-link" aria-label={'Open report ' + run.id} to={'/reports/' + run.id}><ArrowUpRight size={17} /></Link></td></tr>)}</tbody></table></div>
}
