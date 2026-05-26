'use strict';

const http = require('http');
const path = require('path');
const { EventEmitter } = require('events');
const express = require('express');
const { WebSocketServer } = require('ws');
const { localIso } = require('./time');

/**
 * Live dashboard server: static graph page, a /api/status JSON endpoint (so any
 * client — including Claude Code via curl — can read the current vitals), a
 * WebSocket feed that streams each RMSSD/HR update to connected browsers, and a
 * small control channel (POST /api/baseline/reset) that the monitor subscribes
 * to via the returned `events` emitter.
 */
function createServer({ port = 3000, log = () => {} } = {}) {
  const app = express();
  const events = new EventEmitter();
  app.use(express.json());
  app.use(express.static(path.join(__dirname, '..', 'public')));

  let latest = {
    connected: false,
    user: 1,
    hr: null,
    rmssd: null,
    sdnn: null,
    rrCount: 0,
    baseline: null,
    calibration: 0,
    state: null,
    respiration: null,
    respirationConfidence: null,
    respirationPreview: false,
    battery: null,
    updatedAt: null,
  };

  app.get('/api/status', (_req, res) => res.json(latest));

  // Control channel: re-take the resting baseline. The monitor listens on the
  // returned `events` emitter and calls baseline.reset().
  app.post('/api/baseline/reset', (_req, res) => {
    events.emit('baseline-reset');
    res.json({ ok: true });
  });

  // Re-derive the resting baseline from the whole session collected so far (vs.
  // /reset, which recalibrates over the next minute). The monitor computes it
  // synchronously and replies through the callback with whether it applied.
  app.post('/api/baseline/full', (_req, res) => {
    const handled = events.emit('baseline-full', (result) => res.json(result));
    if (!handled) res.json({ ok: true, applied: false, reason: 'no-listener' });
  });

  // Switch the active user profile (1-5). The monitor loads that user's saved
  // resting baseline (if recent), separates their CSV log, and recalibrates.
  app.post('/api/user', (req, res) => {
    const n = Number(req.body && req.body.user);
    if (!Number.isInteger(n) || n < 1 || n > 5) {
      return res.status(400).json({ ok: false, error: 'user must be an integer 1-5' });
    }
    events.emit('user-switch', n);
    res.json({ ok: true, user: n });
  });

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
        events,
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
