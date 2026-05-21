'use strict';

const http = require('http');
const path = require('path');
const express = require('express');
const { WebSocketServer } = require('ws');
const { localIso } = require('./time');

/**
 * Live dashboard server: static graph page, a /api/status JSON endpoint (so any
 * client — including Claude Code via curl — can read the current vitals), and a
 * WebSocket feed that streams each RMSSD/HR update to connected browsers.
 */
function createServer({ port = 3000, log = () => {} } = {}) {
  const app = express();
  app.use(express.static(path.join(__dirname, '..', 'public')));

  let latest = {
    connected: false,
    hr: null,
    rmssd: null,
    sdnn: null,
    rrCount: 0,
    updatedAt: null,
  };

  app.get('/api/status', (_req, res) => res.json(latest));

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server });

  wss.on('connection', (ws) => {
    ws.send(JSON.stringify({ type: 'status', data: latest }));
  });

  function broadcast(type, data) {
    const msg = JSON.stringify({ type, data });
    for (const client of wss.clients) {
      if (client.readyState === 1) client.send(msg);
    }
  }

  function setStatus(partial) {
    latest = { ...latest, ...partial, updatedAt: localIso() };
    broadcast('status', latest);
  }

  function pushPoint(point) {
    broadcast('point', point);
  }

  return new Promise((resolve, reject) => {
    // EADDRINUSE / listen failures are emitted on BOTH the http server and the
    // attached WebSocketServer; handle both so neither throws unhandled.
    const onStartupError = (err) => reject(err);
    server.once('error', onStartupError);
    wss.once('error', onStartupError);
    server.listen(port, () => {
      server.removeListener('error', onStartupError);
      wss.removeListener('error', onStartupError);
      server.on('error', (e) => console.error('HTTP server error:', e.message));
      wss.on('error', (e) => console.error('WebSocket server error:', e.message));
      log(`Dashboard:  http://localhost:${port}`);
      log(`Status API: http://localhost:${port}/api/status`);
      resolve({
        setStatus,
        pushPoint,
        getStatus: () => latest,
        close: () =>
          new Promise((r) => {
            // Terminate live WS clients first, otherwise server.close() hangs
            // until every dashboard tab disconnects on its own.
            for (const client of wss.clients) client.terminate();
            wss.close(() => server.close(r));
          }),
      });
    });
  });
}

module.exports = { createServer };
