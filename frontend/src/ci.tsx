import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Copy, KeyRound } from 'lucide-react'
import { api, date, errorMessage, useResource, type ProjectBaseline } from './api'
import { Empty, Notice } from './ui'

type TokenStatus = { configured: boolean; baselineId?: number | null; baselineName?: string | null; createdAt?: string | null }

export function ProjectCi({ id }: { id: string }) {
  const tokenPath = '/projects/' + id + '/ci-token'
  const tokenStatus = useResource<TokenStatus>(tokenPath)
  const baselines = useResource<ProjectBaseline[]>('/projects/' + id + '/baselines')
  const [selected, setSelected] = useState('')
  const [confirm, setConfirm] = useState<'replace' | 'revoke'>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [issuedToken, setIssuedToken] = useState('')
  const [visible, setVisible] = useState(false)
  const [copyMessage, setCopyMessage] = useState('')
  const current = tokenStatus.data
  const selectedBaseline = baselines.data?.find(baseline => String(baseline.id) === selected)
  const canIssue = !!current && !tokenStatus.error && !tokenStatus.loading
    && !!selectedBaseline && !baselines.error && !baselines.loading

  async function update(action: 'issue' | 'revoke') {
    setBusy(true)
    setError('')
    try {
      if (action === 'issue' && selectedBaseline) {
        const { data } = await api.post<TokenStatus & { token: string }>(tokenPath, { baselineId: selectedBaseline.id })
        setIssuedToken(data.token)
      } else if (action === 'revoke') {
        await api.delete(tokenPath)
        setIssuedToken('')
      }
      setVisible(false)
      setCopyMessage('')
      setConfirm(undefined)
      tokenStatus.reload()
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canIssue || busy) return
    if (current?.configured) setConfirm('replace')
    else void update('issue')
  }

  async function copyToken() {
    try {
      await navigator.clipboard.writeText(issuedToken)
      setCopyMessage('Token copied. Paste it into the GitHub environment secret.')
    } catch {
      setVisible(true)
      setCopyMessage('Clipboard access is unavailable. Select the token field and copy it manually.')
    }
  }

  return <div className="split">
    <section className="panel section" aria-labelledby="project-ci-title">
      <div className="section-heading"><div><p className="eyebrow">REHEARSAL CREDENTIAL</p><h2 id="project-ci-title">GitHub CI token</h2></div><KeyRound size={20} aria-hidden /></div>
      <p className="muted">This token lets CI submit and poll rehearsals for this project using one saved baseline.</p>
      {error && <Notice error>{error}</Notice>}
      {tokenStatus.error && <Notice error><p>{tokenStatus.error}</p><button className="button secondary" disabled={tokenStatus.loading} onClick={tokenStatus.reload}>Retry token status</button></Notice>}
      {tokenStatus.loading && <Notice>Loading token status…</Notice>}
      {current?.configured && <dl className="baseline-summary ci-details">
        <div><dt>Pinned baseline</dt><dd>{current.baselineId != null && <code>v{current.baselineId} · </code>}{current.baselineName}</dd></div>
        {current.createdAt && <div><dt>Issued</dt><dd>{date(current.createdAt)}</dd></div>}
      </dl>}
      {current && !current.configured && <p className="muted ci-details">No active CI token.</p>}
      <form className="form-stack rehearsal-options" aria-busy={busy} onSubmit={submit}>
        <label>Saved baseline<select value={selected} required disabled={busy || !!confirm || baselines.loading || !baselines.data?.length} onChange={event => setSelected(event.target.value)}>
          <option value="">Select a saved baseline</option>
          {baselines.data?.map(baseline => <option value={baseline.id} key={baseline.id}>v{baseline.id} · {baseline.name}</option>)}
        </select></label>
        {baselines.loading && <p className="muted upload-hint" role="status">Loading saved baselines…</p>}
        {baselines.error && <Notice error><p>{baselines.error}</p><button type="button" className="button secondary" disabled={baselines.loading} onClick={baselines.reload}>Retry baselines</button></Notice>}
        {!baselines.loading && !baselines.error && !baselines.data?.length && <Empty>
          Save a baseline from <Link className="text-link" to={'/projects/' + id}>Migrations</Link> before issuing a token.
        </Empty>}
        {confirm ? <div className="form-stack">
          <Notice>{confirm === 'replace'
            ? 'Replacing this token invalidates the previous credential immediately.'
            : 'Revoking this token prevents future CI requests from using it.'}</Notice>
          <div className="actions">
            <button type="button" className={confirm === 'revoke' ? 'button danger' : 'button primary'} disabled={busy || (confirm === 'replace' && !canIssue)}
              onClick={() => void update(confirm === 'replace' ? 'issue' : 'revoke')}>{busy ? 'Updating…' : confirm === 'replace' ? 'Confirm replacement' : 'Confirm revocation'}</button>
            <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirm(undefined)}>Cancel</button>
          </div>
        </div> : <div className="actions">
          <button className="button primary" disabled={busy || !canIssue}>{busy ? 'Issuing…' : current?.configured ? 'Replace token' : 'Issue token'}</button>
          {current?.configured && <button type="button" className="button danger" disabled={busy || tokenStatus.loading || !!tokenStatus.error} onClick={() => setConfirm('revoke')}>Revoke token</button>}
        </div>}
      </form>
      {issuedToken && <div className="form-stack rehearsal-options" aria-labelledby="issued-token-title">
        <h3 id="issued-token-title">New CI token</h3>
        <Notice>Copy the token before leaving this tab. A future visit shows only its metadata.</Notice>
        <label>One-time token<input type={visible ? 'text' : 'password'} className="mono" value={issuedToken} readOnly autoComplete="off"
          spellCheck={false} onFocus={event => event.currentTarget.select()} /></label>
        <div className="actions">
          <button className="button primary" disabled={busy} onClick={() => void copyToken()}><Copy size={16} /> Copy token</button>
          <button className="button secondary" aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? 'Hide token' : 'Show token'}</button>
          <button className="button secondary" onClick={() => { setIssuedToken(''); setCopyMessage(''); setVisible(false) }}>Dismiss</button>
        </div>
        {copyMessage && <p role="status" className="muted upload-hint">{copyMessage}</p>}
      </div>}
    </section>
    <section className="panel section ci-setup" aria-labelledby="ci-setup-title">
      <div className="section-heading"><div><p className="eyebrow">GITHUB ACTIONS</p><h2 id="ci-setup-title">Configure the workflow</h2></div></div>
      <ol>
        <li>Copy <code>deployment/github/schema-rehearsal.yml</code> to <code>.github/workflows/schema-rehearsal.yml</code> in the repository you want to check. Include <code>scripts/github_rehearsal.py</code> on its protected base branch.</li>
        <li>In the repository’s <strong>Settings → Environments</strong>, configure the protected <code>schema-rehearsal</code> environment.</li>
        <li>Use a self-hosted Windows x64 runner with Python 3 and the <code>schema-rehearsal</code> runner label.</li>
        <li>Add <code>SR_CI_TOKEN</code> and <code>SR_CI_URL</code> as environment secrets. Use the issued token and the API URL reachable by your runner.</li>
        <li>Set <code>SR_MIGRATIONS_PATH</code> as an environment variable for the SQL directory. Optionally set <code>SR_REPORT_URL</code> for links to reports.</li>
      </ol>
      <p className="muted">The template checks same-repository pull requests targeting <code>main</code>. Pass credentials through GitHub secrets and keep command tracing disabled so the token stays out of workflow logs.</p>
      <a className="text-link" href="https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments" target="_blank" rel="noreferrer">GitHub environment setup</a>
    </section>
  </div>
}
