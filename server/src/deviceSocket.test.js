const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const { WebSocket } = require('ws');

// Same isolation pattern as store.test.js: point the store at a throwaway
// db file before anything requires it, so this suite doesn't touch the dev
// db or leak state into other test files.
const dbPath = path.join(os.tmpdir(), `a11y-device-socket-test-${process.pid}-${Date.now()}.db`);
process.env.A11Y_DB_PATH = dbPath;

const store = require('./store');
const { attachDeviceSocket } = require('./deviceSocket');
const { createWsRouter } = require('./wsRouter');

let server;
let device;
let port;
let broadcasts;

// Buffers every message from the moment the socket is created (not from
// whenever a test happens to call nextMessage) — otherwise a message the
// server sends immediately on connection (the initial control push) can
// arrive and fire 'message' before a test gets around to awaiting it,
// and a `.once('message', ...)` listener registered after the fact would
// then wait forever for an event that already happened.
function connectClient() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:${port}/ws/device`);
    const queue = [];
    const waiters = [];
    ws.on('message', (data) => {
      const msg = JSON.parse(data.toString());
      if (waiters.length > 0) waiters.shift()(msg);
      else queue.push(msg);
    });
    ws.nextMessage = () => new Promise((res) => {
      if (queue.length > 0) res(queue.shift());
      else waiters.push(res);
    });
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

function nextMessage(ws) {
  return ws.nextMessage();
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

before(async () => {
  server = http.createServer();
  broadcasts = [];
  const wsRouter = createWsRouter(server);
  device = attachDeviceSocket(wsRouter, { broadcast: (msg) => broadcasts.push(msg) });
  await new Promise((resolve) => server.listen(0, resolve));
  port = server.address().port;
});

beforeEach(() => {
  store.clearIssues();
  broadcasts.length = 0;
});

after(async () => {
  device.close();
  await new Promise((resolve) => server.close(resolve));
  try { store.close(); } catch { /* already closed */ }
  try { fs.rmSync(dbPath, { force: true }); } catch { /* ignore */ }
});

test('connecting sends the current control state immediately and marks the device online', async () => {
  store.setControl({ targetPackage: 'com.example.app', auditing: true });

  const ws = await connectClient();
  const first = await nextMessage(ws);

  assert.deepEqual(first, { type: 'control', control: store.getControl() });
  assert.equal(device.isOnline(), true);
  assert.ok(broadcasts.some((m) => m.type === 'device' && m.online === true));

  ws.close();
  await wait(30);
});

test('a report message is stored and broadcast as issues', async () => {
  const ws = await connectClient();
  await nextMessage(ws); // discard the initial control push

  ws.send(JSON.stringify({
    type: 'report',
    packageName: 'com.example.app',
    screen: 'MainScreen',
    timestamp: 1234,
    issues: [{ severity: 'serious', wcagSC: '1.4.3', wcagLevel: 'AA', elementDescription: 'Text', description: 'Low contrast' }],
  }));

  await wait(30);

  const stored = store.getIssues();
  assert.equal(stored.length, 1);
  assert.equal(stored[0].description, 'Low contrast');
  const issuesBroadcast = broadcasts.find((m) => m.type === 'issues');
  assert.ok(issuesBroadcast, 'expected an issues broadcast');
  assert.equal(issuesBroadcast.issues.length, 1);

  ws.close();
  await wait(30);
});

test('pushControl sends the new control state to the connected device', async () => {
  const ws = await connectClient();
  await nextMessage(ws); // discard the initial control push

  const pending = nextMessage(ws);
  device.pushControl({ targetPackage: 'com.example.other', auditing: true });
  const msg = await pending;

  assert.deepEqual(msg, { type: 'control', control: { targetPackage: 'com.example.other', auditing: true } });

  ws.close();
  await wait(30);
});

test('closing the socket marks the device offline', async () => {
  const ws = await connectClient();
  await nextMessage(ws);
  assert.equal(device.isOnline(), true);

  ws.close();
  await wait(50);

  assert.equal(device.isOnline(), false);
  assert.ok(broadcasts.some((m) => m.type === 'device' && m.online === false));
});

test('malformed JSON from the device is ignored, not crashing the connection', async () => {
  const ws = await connectClient();
  await nextMessage(ws);

  ws.send('not json{{{');
  await wait(30);

  // Connection must still be usable afterwards.
  ws.send(JSON.stringify({
    type: 'report',
    packageName: 'com.example.app',
    issues: [{ severity: 'moderate', wcagSC: '1.1.1', wcagLevel: 'A', elementDescription: 'Icon', description: 'No name' }],
  }));
  await wait(30);

  assert.equal(store.getIssues().length, 1);
  assert.equal(ws.readyState, WebSocket.OPEN);

  ws.close();
  await wait(30);
});

test('attachDeviceSocket composes with a router that also has another route registered', async () => {
  // Integration check that attachDeviceSocket correctly claims only its own
  // path on a shared router, alongside a stand-in for the dashboard's own
  // route -- the actual path-vs-path routing behavior (including rejecting
  // an unrecognized path) is covered directly and more thoroughly in
  // wsRouter.test.js, not duplicated here.
  const dashboardServer = http.createServer();
  const dashboardRouter = createWsRouter(dashboardServer);
  const dashboardWss = dashboardRouter.route('/', { exact: true });
  dashboardWss.on('connection', (ws) => ws.send(JSON.stringify({ type: 'init' })));
  const dashboardDevice = attachDeviceSocket(dashboardRouter, { broadcast: () => {} });

  await new Promise((resolve) => dashboardServer.listen(0, resolve));
  const dashboardPort = dashboardServer.address().port;

  const ws = new WebSocket(`ws://localhost:${dashboardPort}/ws/device`);
  const first = await new Promise((resolve, reject) => {
    ws.once('message', (data) => resolve(JSON.parse(data.toString())));
    ws.once('error', reject);
  });

  assert.equal(first.type, 'control', `expected the device protocol, got ${JSON.stringify(first)}`);

  ws.close();
  dashboardDevice.close();
  await new Promise((resolve) => dashboardServer.close(resolve));
});

test('a report with no issues is not stored or broadcast', async () => {
  const ws = await connectClient();
  await nextMessage(ws);

  ws.send(JSON.stringify({ type: 'report', packageName: 'com.example.app', issues: [] }));
  await wait(30);

  assert.equal(store.getIssues().length, 0);
  assert.equal(broadcasts.some((m) => m.type === 'issues'), false);

  ws.close();
  await wait(30);
});
