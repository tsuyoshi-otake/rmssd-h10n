#!/usr/bin/env node
'use strict';

// Isolates Codex's High finding: server.close() must resolve even with a live
// WebSocket client connected. Connects a client, then closes within a deadline.
const WebSocket = require('ws');
const { createServer } = require('../src/server');

(async () => {
  const srv = await createServer({ port: 3099, log: () => {} });
  const ws = new WebSocket('ws://localhost:3099');
  await new Promise((r) => ws.on('open', r));
  console.log('WS client connected; calling server.close()...');

  const closed = srv.close();
  const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error('server.close() HUNG')), 3000));
  try {
    await Promise.race([closed, timeout]);
    console.log('PASS: server.close() resolved with a live WS client');
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    process.exit(1);
  }
})();
