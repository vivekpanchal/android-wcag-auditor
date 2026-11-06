import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import './App.css';
import { filterIssues, groupIssues, parseFix } from './logic';
import { API, AuthError, apiFetch, authFromUrl } from './api';
import { download, toCsv, toHtml } from './export';

const SEVERITIES = ['critical', 'serious', 'moderate', 'minor'];

// Bounds are in the screenshot's own pixel space (same coordinate system the
// Android accessibility service reads them in), so the highlight box is
// positioned as a percentage of the image's natural size — no separate
// screen-size field needed from the device.
export function ScreenshotWithHighlight({ src, bounds, alt, wrapClassName, imgClassName, onClick }) {
  const [natural, setNatural] = useState(null);
  return (
    <div className={wrapClassName} onClick={onClick}>
      <img
        className={imgClassName}
        src={src}
        alt={alt}
        onLoad={(e) => setNatural({ w: e.target.naturalWidth, h: e.target.naturalHeight })}
      />
      {bounds && natural && (
        <div
          className="issue-highlight"
          style={{
            left: `${(bounds.x / natural.w) * 100}%`,
            top: `${(bounds.y / natural.h) * 100}%`,
            width: `${(bounds.width / natural.w) * 100}%`,
            height: `${(bounds.height / natural.h) * 100}%`,
          }}
        />
      )}
    </div>
  );
}

function FixPanel({ fix }) {
  if (!fix) return null;
  const snippets = [
    { key: 'compose', label: 'Compose', code: fix.compose },
    { key: 'views', label: 'Views', code: fix.views },
  ].filter((s) => s.code);
  if (fix.framework !== 'compose') snippets.reverse();
  return (
    <details className="issue-fix">
      <summary>How to fix: {fix.summary}</summary>
      {snippets.map((s) => (
        <div key={s.key} className="fix-snippet">
          <p className="fix-snippet-label">{s.label}{s.key === fix.framework && ' (detected)'}</p>
          <pre><code>{s.code}</code></pre>
        </div>
      ))}
      {fix.docUrl?.startsWith('https://') && (
        <a href={fix.docUrl} target="_blank" rel="noreferrer">Android docs</a>
      )}
    </details>
  );
}

function IssueCard({ issue, onShowScreenshot }) {
  return (
    <div className={`issue-card severity-${issue.severity || 'unknown'}`}>
      <div className="issue-card-header">
        <span className={`badge badge-severity severity-${issue.severity}`}>
          <span className="severity-dot" />
          {issue.severity}
        </span>
        <span className="badge badge-wcag">WCAG {issue.wcagSC} ({issue.wcagLevel})</span>
        <span className="issue-time">{new Date(issue.timestamp).toLocaleTimeString()}</span>
      </div>
      <p className="issue-element"><strong>{issue.elementDescription}</strong></p>
      <p className="issue-description">{issue.description}</p>
      <FixPanel fix={parseFix(issue.suggestedFix)} />
      {issue.screenshotUrl && (
        <ScreenshotWithHighlight
          src={issue.screenshotUrl}
          bounds={issue.bounds}
          alt={`Screenshot: ${issue.elementDescription}`}
          wrapClassName="issue-thumb-wrap"
          imgClassName="issue-thumb"
          onClick={() => onShowScreenshot(issue)}
        />
      )}
    </div>
  );
}

export default function App() {
  const [issues, setIssues] = useState([]);
  const [control, setControl] = useState({ targetPackage: null, auditing: false });
  const [controlBusy, setControlBusy] = useState(false);
  const [apps, setApps] = useState([]);
  const [streamUp, setStreamUp] = useState(true); // true until proven down, so the banner doesn't flash
  const [authFailed, setAuthFailed] = useState(false);
  const authFailedRef = useRef(false); // the effect closure would see stale state
  const streamedRef = useRef([]);
  const clearGenRef = useRef(0);
  const [severityFilter, setSeverityFilter] = useState('all');
  const [levelFilter, setLevelFilter] = useState('all');
  const [screenFilter, setScreenFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [lightbox, setLightbox] = useState(null);

  const onApiError = useCallback((e) => {
    if (!(e instanceof AuthError)) return;
    authFailedRef.current = true;
    setAuthFailed(true);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let retryTimer;
    let source;
    const getJson = async (path) => (await apiFetch(path)).json();

    // Issues that streamed in while the request was in flight are merged back;
    // a clear in the meantime makes the response stale, so it is dropped.
    const refetch = async () => {
      streamedRef.current = [];
      const gen = clearGenRef.current;
      try {
        const [list, ctl] = await Promise.all([getJson('/issues'), getJson('/control')]);
        if (cancelled) return;
        setControl(ctl);
        if (gen !== clearGenRef.current) return;
        const seen = new Set();
        setIssues([...list, ...streamedRef.current].filter((i) => !seen.has(i.id) && seen.add(i.id)));
      } catch (e) {
        onApiError(e);
      }
    };

    const connect = () => {
      if (cancelled || authFailedRef.current) return;
      source = new EventSource(`${API}/events`);
      source.onopen = () => {
        setStreamUp(true);
        refetch();
      };
      source.onerror = () => {
        setStreamUp(false);
        if (source.readyState === EventSource.CLOSED && !authFailedRef.current) {
          retryTimer = setTimeout(connect, 2000);
        }
      };
      source.addEventListener('issue', (e) => {
        const issue = JSON.parse(e.data);
        streamedRef.current.push(issue);
        setIssues((prev) => (prev.some((i) => i.id === issue.id) ? prev : [...prev, issue]));
      });
      source.addEventListener('clear', () => {
        clearGenRef.current += 1;
        streamedRef.current = [];
        setIssues([]);
      });
      source.addEventListener('control', (e) => setControl(JSON.parse(e.data)));
    };

    (async () => {
      await authFromUrl();
      if (cancelled) return;
      connect();
      refetch();
      getJson('/apps').then((a) => !cancelled && setApps(a)).catch(onApiError);
    })();

    return () => {
      cancelled = true;
      clearTimeout(retryTimer);
      source?.close();
    };
  }, [onApiError]);

  // The dashboard no longer picks the target — that's chosen on the phone,
  // in the Auditor app's own app list — so this is purely for a friendlier
  // display of whatever control.targetPackage the phone last set.
  const targetApp = useMemo(
    () => apps.find((a) => a.packageName === control.targetPackage),
    [apps, control.targetPackage]
  );

  const screens = useMemo(() => [...new Set(issues.map((i) => i.screen).filter(Boolean))], [issues]);

  const severityCounts = useMemo(() => {
    const counts = { critical: 0, serious: 0, moderate: 0, minor: 0 };
    for (const issue of issues) if (issue.severity in counts) counts[issue.severity] += 1;
    return counts;
  }, [issues]);

  const filtered = useMemo(
    () => filterIssues(issues, { severityFilter, levelFilter, screenFilter, search }),
    [issues, severityFilter, levelFilter, screenFilter, search]
  );

  const grouped = useMemo(() => groupIssues(filtered), [filtered]);

  const clearSession = async () => {
    try {
      await apiFetch('/issues', { method: 'DELETE' });
      setIssues([]);
    } catch (e) {
      onApiError(e);
    }
  };

  const toggleAuditing = async () => {
    const nextAuditing = !control.auditing;
    if (nextAuditing && !control.targetPackage) return;
    setControlBusy(true);
    try {
      const res = await apiFetch('/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetPackage: control.targetPackage, auditing: nextAuditing }),
      });
      if (res.ok) setControl(await res.json());
    } catch (e) {
      onApiError(e);
    } finally {
      setControlBusy(false);
    }
  };

  return (
    <div className="app">
      <div role="status" aria-live="polite" className="stream-banner">
        {!streamUp && 'Device disconnected — reconnecting…'}
      </div>
      <header className="topbar">
        <div>
          <h1>A11y Auditor</h1>
        </div>
        <div className="actions">
          <button className="btn" onClick={async () => download('a11y-report.html', await toHtml(issues), 'text/html')}>Export HTML</button>
          <button className="btn" onClick={() => download('a11y-report.csv', toCsv(issues), 'text/csv')}>Export CSV</button>
          <button className="btn btn-danger" onClick={clearSession}>Clear Session</button>
        </div>
      </header>

      {authFailed ? (
        <p className="empty">Not signed in to this device's dashboard. Reopen it with "Open dashboard" in the A11y Auditor app, or use "Copy laptop URL".</p>
      ) : (<>
      <section className="control-bar">
        <div className="target-display">
          <span className="target-display-label">Target</span>
          {control.targetPackage ? (
            <span className="target-display-value">
              <span className="target-app-name">{targetApp ? targetApp.appName : control.targetPackage}</span>
              {targetApp && <span className="target-app-package">{control.targetPackage}</span>}
            </span>
          ) : (
            <span className="target-display-empty">None picked yet — choose one in the Auditor app on your phone.</span>
          )}
        </div>
        <button
          className={`btn btn-primary ${control.auditing ? 'btn-danger' : ''}`}
          onClick={toggleAuditing}
          disabled={controlBusy || (!control.auditing && !control.targetPackage)}
        >
          {control.auditing ? 'Stop Auditing' : 'Start Auditing'}
        </button>
        <p className="control-status">
          {!control.auditing && control.targetPackage && 'Not auditing — hit Start when ready.'}
          {!control.auditing && !control.targetPackage && 'Pick a target app on your phone, then hit Start.'}
          {control.auditing && <>Auditing <code>{control.targetPackage}</code></>}
        </p>
      </section>

      <section className="stat-row">
        <div className="stat-tile stat-total">
          <span className="stat-value">{issues.length}</span>
          <span className="stat-label">Total issues</span>
        </div>
        {SEVERITIES.map((sev) => (
          <div key={sev} className={`stat-tile severity-${sev}`}>
            <span className="stat-value">{severityCounts[sev]}</span>
            <span className="stat-label">
              <span className="severity-dot" />
              {sev}
            </span>
          </div>
        ))}
      </section>

      <div className="filters">
        <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value)}>
          <option value="all">All severities</option>
          <option value="critical">Critical</option>
          <option value="serious">Serious</option>
          <option value="moderate">Moderate</option>
          <option value="minor">Minor</option>
        </select>
        <select value={levelFilter} onChange={(e) => setLevelFilter(e.target.value)}>
          <option value="all">All WCAG levels</option>
          <option value="A">A</option>
          <option value="AA">AA</option>
          <option value="AAA">AAA</option>
        </select>
        <select value={screenFilter} onChange={(e) => setScreenFilter(e.target.value)}>
          <option value="all">All screens</option>
          {screens.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input
          type="search"
          placeholder="Search issues…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <main>
        {grouped.size === 0 && <p className="empty">No issues yet. Navigate the target app to start finding them.</p>}
        {[...grouped.entries()].map(([screen, screenIssues]) => (
          <section key={screen} className="screen-group">
            <h2>{screen} <span className="screen-count">({screenIssues.length})</span></h2>
            <div className="issue-list">
              {screenIssues.map((issue) => (
                <IssueCard key={issue.id} issue={issue} onShowScreenshot={setLightbox} />
              ))}
            </div>
          </section>
        ))}
      </main>

      {lightbox && (
        <div className="lightbox" onClick={() => setLightbox(null)}>
          <ScreenshotWithHighlight
            src={lightbox.screenshotUrl}
            bounds={lightbox.bounds}
            alt="Enlarged screenshot"
            wrapClassName="lightbox-img-wrap"
          />
        </div>
      )}
      </>)}
    </div>
  );
}
