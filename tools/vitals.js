#!/usr/bin/env node
'use strict';

// Tiny reader so Claude Code / a shell can print the current vitals on demand:
//   node tools/vitals.js            -> reads data/status.json
//   node tools/vitals.js --url URL  -> fetches a /api/status endpoint
//   node tools/vitals.js --watch    -> refresh once per second
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const statusPath = argFor('--status') || path.join(__dirname, '..', 'data', 'status.json');
const url = argFor('--url');
const watch = args.includes('--watch');
const json = args.includes('--json'); // emit raw JSON for programmatic / AI use

function argFor(flag) {
  const i = args.indexOf(flag);
  return i >= 0 ? args[i + 1] : null;
}

async function read() {
  if (url) {
    const res = await fetch(url);
    return res.json();
  }
  return JSON.parse(fs.readFileSync(statusPath, 'utf8'));
}

function render(s) {
  const f = (v, u) => (v == null ? '–' : `${v} ${u}`);
  return [
    `connected : ${s.connected ? 'yes' : 'no'} (${s.mode || '?'})`,
    `RMSSD     : ${f(s.rmssd, 'ms')}`,
    `HR        : ${f(s.hr, 'bpm')}`,
    `SDNN      : ${f(s.sdnn, 'ms')}`,
    `RR window : ${s.rrCount ?? '–'}  (total beats ${s.beatsTotal ?? '–'}, rejected ${s.rejected ?? '–'})`,
    `battery   : ${s.battery != null ? `${s.battery}%` : '–'}`,
    `updated   : ${s.updatedAt || '?'}`,
  ].join('\n');
}

async function once() {
  try {
    const s = await read();
    console.log(json ? JSON.stringify(s) : render(s));
  } catch (e) {
    if (json) console.log(JSON.stringify({ ok: false, error: 'no_status', message: e.message }));
    else console.error('No live status available yet:', e.message);
    if (!watch) process.exit(1);
  }
}

(async () => {
  if (watch) {
    setInterval(async () => {
      // Keep STDOUT pure JSON (NDJSON) in --json mode; clear screen only for humans.
      if (json) process.stderr.write('\x1b[2J\x1b[H');
      else process.stdout.write('\x1b[2J\x1b[H');
      await once();
    }, 1000);
  } else {
    await once();
  }
})();
