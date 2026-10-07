import { useEffect, useMemo, useState } from 'react';
import './App.css';
import { filterIssues, groupIssues } from './logic';

const SERVER = 'http://localhost:8080';
const WS_URL = 'ws://localhost:8080';

const SEVERITIES = ['critical', 'serious', 'moderate', 'minor'];

// Bounds are in the screenshot's own pixel space (same coordinate system the
// Android accessibility service reads them in), so the highlight box is
// positioned as a percentage of the image's natural size — no separate
// screen-size field needed from the device.
export function ScreenshotWithHighlight({ screenshot, bounds, alt, wrapClassName, imgClassName, onClick }) {
  const [natural, setNatural] = useState(null);
  return (
    <div className={wrapClassName} onClick={onClick}>
      <img
        className={imgClassName}
        src={`data:image/png;base64,${screenshot}`}
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
      {issue.suggestedFix && <p className="issue-fix">Fix: {issue.suggestedFix}</p>}
      {issue.screenshot && (
        <ScreenshotWithHighlight
          screenshot={issue.screenshot}
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
  const [connected, setConnected] = useState(false);
  const [control, setControl] = useState({ targetPackage: null, auditing: false });
  const [controlBusy, setControlBusy] = useState(false);
  const [apps, setApps] = useState([]);
  const [deviceOnline, setDeviceOnline] = useState(false);
  const [severityFilter, setSeverityFilter] = useState('all');
  const [levelFilter, setLevelFilter] = useState('all');
  const [screenFilter, setScreenFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [lightbox, setLightbox] = useState(null);

  useEffect(() => {
    fetch(`${SERVER}/issues`).then((r) => r.json()).then(setIssues).catch(() => {});
    fetch(`${SERVER}/control`).then((r) => r.json()).then(setControl).catch(() => {});
    fetch(`${SERVER}/apps`).then((r) => r.json()).then(setApps).catch(() => {});
    fetch(`${SERVER}/device`).then((r) => r.json()).then((d) => setDeviceOnline(d.online)).catch(() => {});

    let ws;
    let retryTimer;
    const connect = () => {
      ws = new WebSocket(WS_URL);
      ws.onopen = () => setConnected(true);
      ws.onclose = () => {
        setConnected(false);
        retryTimer = setTimeout(connect, 2000);
      };
      ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'init') {
          setIssues(msg.issues);
          if (msg.control) setControl(msg.control);
          if (msg.apps) setApps(msg.apps);
          if (msg.device) setDeviceOnline(msg.device.online);
        } else if (msg.type === 'issues') {
          setIssues((prev) => {
            const seen = new Set(prev.map((i) => i.id));
            return [...prev, ...msg.issues.filter((i) => !seen.has(i.id))];
          });
        } else if (msg.type === 'clear') setIssues([]);
        else if (msg.type === 'control') setControl(msg.control);
        else if (msg.type === 'apps') setApps(msg.apps);
        else if (msg.type === 'device') setDeviceOnline(msg.online);
      };
    };
    connect();
    return () => {
      clearTimeout(retryTimer);
      ws?.close();
    };
  }, []);

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
    await fetch(`${SERVER}/issues`, { method: 'DELETE' });
    setIssues([]);
  };

  const toggleAuditing = async () => {
    const nextAuditing = !control.auditing;
    if (nextAuditing && (!control.targetPackage || !deviceOnline)) return;
    setControlBusy(true);
    try {
      const res = await fetch(`${SERVER}/control`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetPackage: control.targetPackage, auditing: nextAuditing }),
      });
      if (res.ok) setControl(await res.json());
    } finally {
      setControlBusy(false);
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>A11y Auditor</h1>
          <p className="target">
            <span className={connected ? 'ws-on' : 'ws-off'}>{connected ? 'server live' : 'reconnecting…'}</span>
            {' · '}
            <span className={deviceOnline ? 'ws-on' : 'ws-off'}>
              <span className="status-dot" />
              {deviceOnline ? 'device connected' : 'device disconnected'}
            </span>
          </p>
        </div>
        <div className="actions">
          <a className="btn" href={`${SERVER}/issues/export`} target="_blank" rel="noreferrer">Export HTML</a>
          <a className="btn" href={`${SERVER}/issues/export?format=csv`} target="_blank" rel="noreferrer">Export CSV</a>
          <button className="btn btn-danger" onClick={clearSession}>Clear Session</button>
        </div>
      </header>

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
          disabled={controlBusy || (!control.auditing && (!control.targetPackage || !deviceOnline))}
        >
          {control.auditing ? 'Stop Auditing' : 'Start Auditing'}
        </button>
        <p className="control-status">
          {!deviceOnline && !control.auditing && 'No device connected — open the Auditor app on your phone with the accessibility service enabled.'}
          {deviceOnline && !control.auditing && control.targetPackage && 'Not auditing — hit Start when ready.'}
          {deviceOnline && !control.auditing && !control.targetPackage && 'Pick a target app on your phone, then hit Start.'}
          {control.auditing && <>Auditing <code>{control.targetPackage}</code>{!deviceOnline && ' (device currently unreachable — issues won\'t flow until it reconnects)'}</>}
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
            screenshot={lightbox.screenshot}
            bounds={lightbox.bounds}
            alt="Enlarged screenshot"
            wrapClassName="lightbox-img-wrap"
          />
        </div>
      )}
    </div>
  );
}
