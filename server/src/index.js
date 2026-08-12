const express = require('express');
const cors = require('cors');
const http = require('http');
const path = require('path');
const fs = require('fs');
const { WebSocketServer } = require('ws');
const store = require('./store');
const { toCsv, toHtml } = require('./export');

const PORT = process.env.PORT || 8080;
// The Auditor service polls /control every 3s while its accessibility
// service is enabled; two missed polls means it's gone (app closed,
// service disabled, USB unplugged, adb reverse dropped, etc).
const DEVICE_TIMEOUT_MS = 8000;
let deviceOnline = false;

const app = express();
app.use(cors()); // still needed for `vite dev` on :5173 during dashboard development
app.use(express.json({ limit: '10mb' })); // screenshots are base64

// Serve the dashboard's production build if it's been built
// (cd dashboard && npm run build) — one process, one port, for normal use.
// Falls back to nothing if it hasn't been built yet; run the Vite dev
// server separately (npm run dev in dashboard/) while actively working on
// the UI, which still talks to this same API via CORS.
const dashboardDist = path.join(__dirname, '..', '..', 'dashboard', 'dist');
if (fs.existsSync(dashboardDist)) {
  app.use(express.static(dashboardDist));
}

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

function broadcast(msg) {
  const data = JSON.stringify(msg);
  for (const client of wss.clients) {
    if (client.readyState === client.OPEN) client.send(data);
  }
}

wss.on('connection', (ws) => {
  ws.send(JSON.stringify({
    type: 'init',
    issues: store.getIssues(),
    control: store.getControl(),
    apps: store.getAppList(),
    device: { online: deviceOnline, lastSeen: store.getDeviceLastSeen() },
  }));
});

setInterval(() => {
  const lastSeen = store.getDeviceLastSeen();
  const nowOnline = !!lastSeen && Date.now() - lastSeen < DEVICE_TIMEOUT_MS;
  if (nowOnline === deviceOnline) return;
  deviceOnline = nowOnline;
  broadcast({ type: 'device', online: deviceOnline, lastSeen });
}, 2000);

app.post('/report', (req, res) => {
  const { packageName, issues } = req.body || {};
  if (!packageName || !Array.isArray(issues)) {
    return res.status(400).json({ error: 'packageName and issues[] are required' });
  }
  const stored = store.addReport({
    packageName,
    screen: req.body.screen,
    timestamp: req.body.timestamp || Date.now(),
    screenshot: req.body.screenshot,
    issues,
  });
  broadcast({ type: 'issues', issues: stored });
  res.status(201).json({ received: stored.length });
});

app.get('/issues', (req, res) => {
  res.json(store.getIssues());
});

app.get('/issues/export', (req, res) => {
  const format = req.query.format === 'csv' ? 'csv' : 'html';
  const issues = store.getIssues();
  if (format === 'csv') {
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'attachment; filename="a11y-report.csv"');
    res.send(toCsv(issues));
  } else {
    res.setHeader('Content-Type', 'text/html');
    res.setHeader('Content-Disposition', 'attachment; filename="a11y-report.html"');
    res.send(toHtml(issues));
  }
});

app.delete('/issues', (req, res) => {
  store.clearIssues();
  broadcast({ type: 'clear' });
  res.status(204).end();
});

// The device can't be pushed to (adb reverse only lets it reach us), so the
// Auditor app polls GET /control; the dashboard sets desired state via POST.
// This poll also doubles as the device's heartbeat — see /device below.
app.get('/control', (req, res) => {
  store.touchDevice();
  res.json(store.getControl());
});

app.get('/device', (req, res) => {
  res.json({ online: deviceOnline, lastSeen: store.getDeviceLastSeen() });
});

app.post('/control', (req, res) => {
  const { targetPackage, auditing } = req.body || {};
  if (typeof auditing !== 'boolean') {
    return res.status(400).json({ error: 'auditing (boolean) is required' });
  }
  if (auditing && !targetPackage) {
    return res.status(400).json({ error: 'targetPackage is required to start auditing' });
  }
  if (auditing && !deviceOnline) {
    return res.status(409).json({ error: 'device is not connected — cannot start auditing' });
  }
  const control = store.setControl({ targetPackage: targetPackage || null, auditing });
  broadcast({ type: 'control', control });
  res.json(control);
});

// Reported once by the Auditor app (installed-apps enumeration happens on
// device, not here) so the dashboard can offer a real picker.
app.get('/apps', (req, res) => {
  res.json(store.getAppList());
});

app.post('/apps', (req, res) => {
  const apps = req.body;
  if (!Array.isArray(apps)) {
    return res.status(400).json({ error: 'body must be an array of {appName, packageName}' });
  }
  const stored = store.setAppList(apps);
  broadcast({ type: 'apps', apps: stored });
  res.json({ received: stored.length });
});

server.listen(PORT, () => {
  console.log(`A11y Auditor server listening on http://localhost:${PORT}`);
});
