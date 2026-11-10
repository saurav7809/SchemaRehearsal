import { useEffect, useState, type MouseEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Check, Circle, Clock3, Download, FileCheck2, LoaderCircle, X } from 'lucide-react'
import { api, assertionLabels, date, errorMessage, pending, useResource, type QueryResult, type Run } from './api'
import { Badge, Empty, Heading, Notice } from './ui'

const saveBaselineStage = 'Save baseline snapshot'
const stages = ['Database snapshot', 'Temporary database', 'Migration execution', 'Compatibility testing', saveBaselineStage, 'Risk analysis', 'Final report']

export function ReportPage() {
  const { id } = useParams()
  return <ReportDetail key={id} id={id!} />
}

function ReportDetail({ id }: { id: string }) {
  const resource = useResource<Run>('/reports/' + id)
  const run = resource.data
  const active = run && pending(run)
  const { reload, status, loading, failures } = resource
  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState('')
  useEffect(() => {
    const permanentFailure = status && status >= 400 && status < 500 && ![408, 429].includes(status)
    if (!active || loading || permanentFailure) return
    const delay = Math.min(1500 * 2 ** failures, 30000)
    const timer = window.setTimeout(reload, delay)
    return () => window.clearTimeout(timer)
  }, [active, failures, loading, reload, status])

  async function downloadReport(event: MouseEvent<HTMLAnchorElement>, runId: number) {
    event.preventDefault()
    if (downloading) return
    setDownloading(true)
    setDownloadError('')
    try {
      const response = await api.get<Blob>('/reports/' + runId + '/download', { responseType: 'blob' })
      if (response.data.type !== 'application/pdf') {
        setDownloadError('The server returned an invalid PDF. Try again.')
        return
      }
      const url = URL.createObjectURL(response.data)
      const link = document.createElement('a')
      link.href = url
      link.download = 'rehearsal-' + runId + '.pdf'
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) {
      setDownloadError(errorMessage(e))
    } finally {
      setDownloading(false)
    }
  }

  if (!run || status === 403 || status === 404) return <>
    <Link className="text-link back" to="/reports"><ArrowLeft size={15} /> Reports</Link>
    <Notice error={!!resource.error}>{resource.error || 'Loading rehearsal…'}</Notice>
    {resource.error && <div><button className="button secondary" disabled={loading} onClick={reload}>Retry report</button></div>}
  </>
  const report = run.report
  const scoring = report.risk
  const lockImpact = report.lockImpact
  const lockImpactTable = lockImpact?.table ?? report.lockImpactTable
  const runStages = report.saveBaselineAs ? stages : stages.filter(name => name !== saveBaselineStage)
  return <><Link className="text-link back" to="/reports"><ArrowLeft size={15} /> Reports</Link>
    <Heading eyebrow={'REHEARSAL #' + run.id} title={run.migrationName} description={date(run.createdAt) + ' · ' + run.durationMs + ' ms elapsed'} action={active ? <Badge value={run.status} /> : <a className="button primary" href={'/api/reports/' + run.id + '/download'} aria-disabled={downloading} onClick={event => void downloadReport(event, run.id)}><Download size={17} /> {downloading ? 'Downloading…' : 'Download PDF'}</a>} />
    {resource.error && <Notice error>
      <p>Could not refresh this report. {resource.error}</p>
      <button className="button secondary" disabled={loading} onClick={reload}>Retry report</button>
    </Notice>}
    {downloadError && <Notice error>{downloadError}</Notice>}
    {report.error && <Notice error><strong>Rehearsal error</strong><pre className="error-evidence">{report.error}</pre></Notice>}
    {report.cleanupError && <Notice error>Temporary database cleanup failed. {report.cleanupError}</Notice>}
    {report.ci && <section className="panel section" aria-labelledby="report-ci-title">
      <div className="section-heading"><h2 id="report-ci-title">GitHub CI provenance</h2></div>
      <dl className="baseline-summary">
        <div><dt>Pull request</dt><dd>{report.ci.repository} #{report.ci.pullRequest}</dd></div>
        <div><dt>Commit</dt><dd><code>{report.ci.commit}</code></dd></div>
      </dl>
    </section>}
    {(report.baseline || report.saveBaselineAs || report.savedBaseline) && <section className="panel section" aria-labelledby="report-baseline-title">
      <div className="section-heading"><h2 id="report-baseline-title">Rehearsal baselines</h2>
        <Link className="text-link" to={'/projects/' + run.projectId + '?tab=Baselines'}>Project baselines</Link>
      </div>
      <dl className="baseline-summary">
        {report.baseline && <div><dt>Starting baseline</dt><dd>
          {report.baseline.id != null && <code>v{report.baseline.id} · </code>}{report.baseline.name}
          {report.baseline.sourceRunId != null && <Link className="text-link" to={'/reports/' + report.baseline.sourceRunId}>Source run #{report.baseline.sourceRunId}</Link>}
        </dd></div>}
        {report.savedBaseline && <div><dt>Saved baseline</dt><dd>
          <code>v{report.savedBaseline.id} · </code>{report.savedBaseline.name}
        </dd></div>}
        {report.saveBaselineAs && !report.savedBaseline && <div><dt>Requested baseline</dt><dd>{report.saveBaselineAs}</dd></div>}
      </dl>
    </section>}
    {lockImpactTable && <section className="panel section" aria-labelledby="report-lock-impact-title">
      <div className="section-heading"><div><p className="eyebrow">TABLE-LEVEL LOCK PROBE</p><h2 id="report-lock-impact-title">Lock impact</h2></div>
        <Badge value={lockImpact?.status ?? (active ? 'PENDING' : 'NOT_RECORDED')} />
      </div>
      <dl className="baseline-summary">
        <div><dt>Table</dt><dd><code>public.{lockImpactTable}</code></dd></div>
        {lockImpact && (lockImpact.status === 'MEASURED' || lockImpact.attempts > 0) && <>
          <div><dt>Attempts</dt><dd>{lockImpact.attempts}</dd></div>
          <div><dt>Blocked attempts</dt><dd>{lockImpact.blockedAttempts}</dd></div>
          <div><dt>Timed-out attempts</dt><dd>{lockImpact.timedOutAttempts}</dd></div>
          <div><dt>Longest observed retry wait</dt><dd>{lockImpact.maxWaitMs == null ? '—' : lockImpact.maxWaitMs + ' ms'}</dd></div>
        </>}
      </dl>
      <p className="muted lock-impact-note">{lockImpact?.note ?? (active
        ? 'The lock-only probe will run during migration execution.'
        : 'The rehearsal ended before lock-impact evidence was recorded.')}</p>
      <p className="muted upload-hint">This probe estimates PostgreSQL table-level write-lock waits in the rehearsal database. Timed-out attempts establish a lower bound.</p>
    </section>}
    <div className="split report-grid"><section className="panel section">
      <div className="section-heading"><div><p className="eyebrow">EXECUTION TIMELINE</p><h2>Testing workflow</h2></div><Badge value={run.status} /></div>
      <ol className="steps">{runStages.map(name => {
        const step = report.steps?.find(item => item.name === name)
        const running = report.activeStep === name || active && run.status === 'QUEUED' && name === stages[0]
        const status = step?.status ?? (running ? 'RUNNING' : active ? 'PENDING' : 'SKIPPED')
        const Icon = status === 'SUCCEEDED' ? Check : status === 'FAILED' ? X : running ? LoaderCircle : Circle
        return <li key={name}><span className={'step-icon ' + status.toLowerCase()}><Icon size={16} /></span><strong>{name}</strong><Badge value={status} /><small>{step ? step.durationMs + ' ms' : '—'}</small></li>
      })}</ol>
    </section><section className="panel section"><div className="section-heading"><div><p className="eyebrow">RISK ASSESSMENT</p><h2>Risk score</h2></div><FileCheck2 size={21} /></div>
      {scoring ? <><div className="risk-number"><strong>{scoring.score}</strong><span>/ 100</span><Badge value={scoring.classification} /></div><progress max={100} value={scoring.score} aria-label="Risk score" />
        <p className="muted">{scoring.uncapped} points before the 100-point cap. Static warnings and observed failures are scored separately.</p>
        {scoring.factors.map((factor, i) => <div className="factor" key={i}><div><strong>{factor.title}</strong><small>{factor.source}</small></div><span>+{factor.points}</span></div>)}
        {!scoring.factors.length && <div className="line-item">No contributing findings.</div>}
      </> : <Empty>{active ? 'The risk score appears after the risk analysis step.' : 'This run has no recorded risk calculation.'}</Empty>}
    </section></div>
    {!!report.migrationFiles?.length && <section className="panel section" aria-labelledby="migration-execution-title">
      <div className="section-heading"><div><p className="eyebrow">FLYWAY EXECUTION ORDER</p><h2 id="migration-execution-title">Migration files</h2></div></div>
      <div className="table-wrap"><table>
        <thead><tr><th scope="col">File</th><th scope="col">Status</th><th scope="col">Duration</th></tr></thead>
        <tbody>{report.migrationFiles.map(file => <tr key={file.name}>
          <td><code>{file.name}</code>{file.error && <pre className="error-evidence">{file.error}</pre>}</td>
          <td><Badge value={file.status} /></td>
          <td>{file.durationMs == null ? '—' : file.durationMs + ' ms'}</td>
        </tr>)}</tbody>
      </table></div>
    </section>}
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">EVIDENCE & REMEDIATION</p><h2>Risk findings</h2></div><span className="count">{report.findings?.length ?? 0} findings</span></div>
      {report.findings?.length ? <div className="finding-grid">{report.findings.map((finding, i) => <article className="finding" key={i}><div className="actions"><Badge value={finding.severity} /><Badge value={finding.source} /></div>
        <h3>{finding.title}</h3>
        {finding.filename && <p className="muted">Migration file <code>{finding.filename}</code></p>}
        {finding.table && <code>{finding.table}{finding.column ? '.' + finding.column : ''}</code>}
        <p>{finding.evidence}</p>{finding.statement && <pre className="code">{finding.statement}</pre>}
        <div className="recommendation"><strong>Recommended approach</strong><p>{finding.recommendation}</p></div>
      </article>)}</div> : <Empty>{active ? 'Findings will appear as evidence is recorded.' : 'No risk findings were recorded for this run.'}</Empty>}
    </section>
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">APPLICATION QUERIES</p><h2>Compatibility evidence</h2></div></div>
      {report.compatibility?.length ? report.compatibility.map(probe => <article className="probe" key={probe.id}>
        <div className="section-heading"><h3>{probe.name} <Badge value={probe.generation} /></h3>
          <span className="actions"><span className="muted upload-hint">Query compatibility</span><Badge value={probe.status} /></span>
        </div>
        <div className="probe-results"><QueryEvidence title="Baseline" result={probe.baseline} /><QueryEvidence title="Migrated" result={probe.migrated} /></div>
      </article>) : <Empty>No compatibility comparisons were recorded.</Empty>}
    </section>
    <section className="panel section"><div className="section-heading"><div><p className="eyebrow">BEFORE & AFTER</p><h2>Schema changes</h2></div><Clock3 size={19} /></div>
      {report.changes?.length ? <div className="table-wrap"><table><thead><tr><th>Object</th><th>Change</th><th>Before</th><th>After</th></tr></thead><tbody>
        {report.changes.map(change => <tr key={change.object}><td><code>{change.object}</code></td><td><Badge value={change.kind} /></td><td>{change.before ?? '—'}</td><td>{change.after ?? '—'}</td></tr>)}
      </tbody></table></div> : <Empty>No schema differences were recorded.</Empty>}
    </section>
  </>
}

function QueryEvidence({ title, result }: { title: string; result?: QueryResult }) {
  const assertion = result?.assertion
  return <div><p className="eyebrow">{title}</p>
    {!result ? <p className="muted">Skipped</p> : result.success
      ? <><p className="muted">{result.sampledRows} sampled rows</p><code>{result.columns.join(', ')}</code></>
      : <pre className="error-evidence">{result.error}</pre>}
    {assertion && <div className="query-assertion">
      <p><strong>Result assertion</strong> <Badge value={assertion.status} /></p>
      <p>{assertionLabels[assertion.kind]} <code>{assertion.expected}</code></p>
      <p className="muted">{assertion.reason}</p>
    </div>}
  </div>
}
