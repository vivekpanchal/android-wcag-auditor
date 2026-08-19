// Dispatches a single http.Server's WebSocket upgrade requests to whichever
// WebSocketServer owns the request's path.
//
// `ws`'s own { server, path } option doesn't safely support more than one
// WebSocketServer on the same http.Server: a WebSocketServer whose path
// doesn't match a given request actively rejects it with a 400 instead of
// leaving it for another listener to try, no matter the registration
// order -- see the deviceSocket.js / dashboard wss coexistence bug this
// was extracted from fixing. Routing through one shared 'upgrade' listener
// avoids that regardless of how many routes get registered.
'use strict';

const { WebSocketServer } = require('ws');

function createWsRouter(httpServer) {
  const routes = [];

  /**
   * Registers a new WebSocketServer at `matchPath` on this router's
   * http.Server. `exact: true` matches the path exactly (e.g. '/'); the
   * default matches as a prefix (e.g. '/ws/device' also matches
   * '/ws/device?foo').
   */
  function route(matchPath, { exact = false } = {}) {
    const wss = new WebSocketServer({ noServer: true });
    const matches = exact
      ? (url) => url === matchPath
      : (url) => !!url && url.startsWith(matchPath);
    routes.push({ matches, wss });
    return wss;
  }

  httpServer.on('upgrade', (req, socket, head) => {
    const found = routes.find((r) => r.matches(req.url));
    if (!found) {
      // No route owns this path (typo, stray client) -- without this,
      // the socket would just sit open forever, since Node doesn't reject
      // a handshake on its own just because *an* 'upgrade' listener exists.
      socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
      socket.destroy();
      return;
    }
    found.wss.handleUpgrade(req, socket, head, (ws) => found.wss.emit('connection', ws, req));
  });

  return { route };
}

module.exports = { createWsRouter };
