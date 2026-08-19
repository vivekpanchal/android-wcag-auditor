// Persistent WebSocket endpoint for the Auditor app itself, at /ws/device —
// distinct from the plain WebSocketServer in index.js that dashboard
// browser tabs connect to. One connection replaces two HTTP paths the
// device used to use: the 3s `GET /control` poll (control is now pushed
// the instant it changes) and the per-finding `POST /report` (reports are
// now sent up the same socket). Connection state also replaces the old
// poll-derived heartbeat — online means a socket is open, not "seen
// within the last 8 seconds".
'use strict';

const { WebSocketServer, WebSocket } = require('ws');
const store = require('./store');

// A silently dropped Wi-Fi/USB link doesn't always deliver a clean TCP
// close, so 'close' alone isn't enough to detect it. Ping on this interval
// and terminate any socket that didn't pong since the last one.
const HEARTBEAT_INTERVAL_MS = 15000;

const DEVICE_PATH = '/ws/device';

function attachDeviceSocket(httpServer, { broadcast }) {
  // noServer + a manual 'upgrade' listener, not { server, path } -- `ws`
  // aborts the handshake with a 400 whenever a WebSocketServer's own path
  // doesn't match, instead of leaving it for the next 'upgrade' listener to
  // try. With another WebSocketServer (the dashboard's, in index.js) also
  // attached to this same http.Server, that races and kills whichever
  // connection loses. Checking the path ourselves and only calling
  // handleUpgrade on a match — doing nothing otherwise — lets both
  // coexist regardless of registration order.
  const wss = new WebSocketServer({ noServer: true });
  httpServer.on('upgrade', (req, socket, head) => {
    if (!req.url || !req.url.startsWith(DEVICE_PATH)) return;
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });
  const clients = new Set();
  let online = false;

  function setOnline(next) {
    if (next === online) return;
    online = next;
    broadcast({ type: 'device', online, lastSeen: store.getDeviceLastSeen() });
  }

  function pushControl(control) {
    const data = JSON.stringify({ type: 'control', control });
    for (const ws of clients) {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    }
  }

  function handleReport(msg) {
    const { packageName, issues } = msg;
    if (!packageName || !Array.isArray(issues)) return;
    const stored = store.addReport({
      packageName,
      screen: msg.screen,
      timestamp: msg.timestamp || Date.now(),
      screenshot: msg.screenshot,
      issues,
    });
    if (stored.length > 0) broadcast({ type: 'issues', issues: stored });
  }

  function handleMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      // Malformed frame -- ignore and keep the connection open rather than
      // taking the whole audit session down over one bad message.
      return;
    }
    if (msg.type === 'report') handleReport(msg);
  }

  wss.on('connection', (ws) => {
    ws.isAlive = true;
    clients.add(ws);
    store.touchDevice();
    setOnline(true);
    ws.send(JSON.stringify({ type: 'control', control: store.getControl() }));

    ws.on('message', (raw) => {
      store.touchDevice();
      handleMessage(raw);
    });
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('close', () => {
      clients.delete(ws);
      if (clients.size === 0) setOnline(false);
    });
    // 'close' still fires after 'error' for a ws connection -- cleanup
    // happens there. This only stops an unhandled 'error' event from
    // crashing the process.
    ws.on('error', () => {});
  });

  const heartbeat = setInterval(() => {
    for (const ws of clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, HEARTBEAT_INTERVAL_MS);
  heartbeat.unref();

  return {
    pushControl,
    isOnline: () => online,
    close: () => {
      clearInterval(heartbeat);
      wss.close();
    },
  };
}

module.exports = { attachDeviceSocket };
