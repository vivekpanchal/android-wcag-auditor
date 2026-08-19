const { test, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { WebSocket } = require('ws');
const { createWsRouter } = require('./wsRouter');

const servers = [];

function startRouter() {
  const server = http.createServer();
  servers.push(server);
  const router = createWsRouter(server);
  return new Promise((resolve) => server.listen(0, () => resolve({ server, router, port: server.address().port })));
}

after(async () => {
  await Promise.all(servers.map((s) => new Promise((resolve) => s.close(resolve))));
});

test('an exact-match route only accepts its own path', async () => {
  const { router, port } = await startRouter();
  const wss = router.route('/', { exact: true });
  wss.on('connection', (ws) => ws.send('root'));

  const ws = new WebSocket(`ws://localhost:${port}/`);
  const msg = await new Promise((resolve, reject) => {
    ws.once('message', (d) => resolve(d.toString()));
    ws.once('error', reject);
  });

  assert.equal(msg, 'root');
  ws.close();
});

test('a prefix route matches the exact path and anything nested under it', async () => {
  const { router, port } = await startRouter();
  const wss = router.route('/ws/device');
  wss.on('connection', (ws) => ws.send('device'));

  const ws = new WebSocket(`ws://localhost:${port}/ws/device`);
  const msg = await new Promise((resolve, reject) => {
    ws.once('message', (d) => resolve(d.toString()));
    ws.once('error', reject);
  });

  assert.equal(msg, 'device');
  ws.close();
});

test('two routes on the same router do not interfere with each other', async () => {
  const { router, port } = await startRouter();
  const rootWss = router.route('/', { exact: true });
  const deviceWss = router.route('/ws/device');
  rootWss.on('connection', (ws) => ws.send('root'));
  deviceWss.on('connection', (ws) => ws.send('device'));

  const [rootMsg, deviceMsg] = await Promise.all([
    new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://localhost:${port}/`);
      ws.once('message', (d) => { resolve(d.toString()); ws.close(); });
      ws.once('error', reject);
    }),
    new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://localhost:${port}/ws/device`);
      ws.once('message', (d) => { resolve(d.toString()); ws.close(); });
      ws.once('error', reject);
    }),
  ]);

  assert.equal(rootMsg, 'root');
  assert.equal(deviceMsg, 'device');
});

test('a path no route owns is rejected with 400, not left hanging', async () => {
  const { router, port } = await startRouter();
  router.route('/', { exact: true });
  router.route('/ws/device');

  const ws = new WebSocket(`ws://localhost:${port}/not-a-real-path`);
  const status = await new Promise((resolve, reject) => {
    ws.once('unexpected-response', (req, res) => resolve(res.statusCode));
    ws.once('open', () => reject(new Error('expected the handshake to be rejected')));
  });

  assert.equal(status, 400);
});
