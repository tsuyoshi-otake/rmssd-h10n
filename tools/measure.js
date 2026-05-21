#!/usr/bin/env node
'use strict';

// One-shot HRV measurement CLI, designed for programmatic / generative-AI use:
// connect to the Polar H10, collect RR intervals for a fixed duration, compute
// RMSSD and related metrics, print a single JSON object to STDOUT, then exit.
//
//   node tools/measure.js                 # 30 s capture, JSON to stdout
//   node tools/measure.js --seconds 60    # longer capture
//   node tools/measure.js --rr            # also include the raw RR series
//   node tools/measure.js --ecg           # use PMD raw ECG instead of HR-RR
//   node tools/measure.js --pretty        # pretty-printed JSON
//
// Progress/diagnostic logs go to STDERR so STDOUT stays clean JSON for piping:
//   node tools/measure.js --seconds 20 | jq .metrics.rmssd_ms
const ble = require('../src/ble');
const pmd = require('../src/pmd');
const { parseHrm } = require('../src/hrm');
const { QRSDetector } = require('../src/qrs');

function parseArgs(argv) {
  const o = { seconds: 30, name: 'polar', mode: 'hr-rr', rr: false, pretty: false, scanTimeout: 30000 };
  for (let i = 2; i < argv.length; i++) {
    switch (argv[i]) {
      case '--seconds': case '-s': o.seconds = Number(argv[++i]); break;
      case '--name': o.name = argv[++i]; break;
      case '--ecg': o.mode = 'ecg'; break;
      case '--rr': o.rr = true; break;
      case '--pretty': o.pretty = true; break;
      case '--scan-timeout': o.scanTimeout = Number(argv[++i]); break;
      case '--help': case '-h':
        process.stderr.write('Usage: node tools/measure.js [--seconds N] [--rr] [--ecg] [--pretty] [--name str]\n');
        process.exit(0);
    }
  }
  return o;
}

const log = (...a) => process.stderr.write(`[measure] ${a.join(' ')}\n`);

function stats(rrMs) {
  const n = rrMs.length;
  const round = (x, d = 1) => Number(x.toFixed(d));
  if (n === 0) {
    return { count: 0, rmssd_ms: null, sdnn_ms: null, hr_bpm: null, meanRr_ms: null, minRr_ms: null, maxRr_ms: null };
  }
  // HR and RR summary are valid from a single interval.
  const mean = rrMs.reduce((a, b) => a + b, 0) / n;
  const base = {
    count: n,
    hr_bpm: round(60000 / mean),
    meanRr_ms: round(mean),
    minRr_ms: Math.round(Math.min(...rrMs)),
    maxRr_ms: Math.round(Math.max(...rrMs)),
  };
  if (n < 2) return { ...base, rmssd_ms: null, sdnn_ms: null };
  // RMSSD: root mean square of the n-1 successive differences.
  let sumSqDiff = 0;
  for (let i = 1; i < n; i++) { const d = rrMs[i] - rrMs[i - 1]; sumSqDiff += d * d; }
  const rmssd = Math.sqrt(sumSqDiff / (n - 1));
  // SDNN: population standard deviation of NN intervals (matches src/rmssd.js).
  const variance = rrMs.reduce((a, b) => a + (b - mean) * (b - mean), 0) / n;
  return { ...base, rmssd_ms: round(rmssd), sdnn_ms: round(Math.sqrt(variance)) };
}

function emit(obj, pretty) {
  process.stdout.write(JSON.stringify(obj, null, pretty ? 2 : 0) + '\n');
}

(async () => {
  const opts = parseArgs(process.argv);
  const startedAt = new Date().toISOString();

  // Single exit point: emit JSON exactly once, then exit. Guarded so the
  // watchdog and the normal path can never double-emit.
  let done = false;
  let watchdog;
  const finish = (result, code) => {
    if (done) return;
    done = true;
    if (watchdog) clearTimeout(watchdog);
    emit(result, opts.pretty);
    process.exit(code);
  };

  // Hard watchdog: guarantee the CLI always emits JSON and exits, even if a BLE
  // operation (connect/discover/subscribe/disconnect) hangs with no resolution.
  const hardDeadlineMs = opts.scanTimeout + opts.seconds * 1000 + 20000;
  watchdog = setTimeout(() => {
    finish({ ok: false, error: 'timeout', message: `no result within ${hardDeadlineMs} ms`, startedAt }, 2);
  }, hardDeadlineMs);

  let peripheral;
  try {
    peripheral = await ble.scanAndConnect({ nameMatch: opts.name, timeoutMs: opts.scanTimeout, log });
  } catch (e) {
    return finish({ ok: false, error: 'connect_failed', message: e.message, startedAt }, 2);
  }
  const deviceName = (peripheral.advertisement && peripheral.advertisement.localName) || peripheral.id;

  const rrMs = []; // accepted beat-to-beat intervals
  let raw = 0; // raw RR / detected beats before filtering

  // Light artifact filter: plausible physiological range + relative jump guard.
  let last = null;
  const accept = (rr) => {
    raw++;
    if (rr < 300 || rr > 2000) return; // implausible -> reject, keep last accepted
    if (last != null && Math.abs(rr - last) / last > 0.3) return; // jump -> reject, don't update last
    rrMs.push(rr);
    last = rr;
  };

  // Everything after connect runs in try/finally so we always unsubscribe / stop
  // / disconnect, on success AND error. Cleanup completes BEFORE finish() (which
  // calls process.exit), so it is never skipped.
  let stop = async () => {};
  let captureError = null;
  try {
    if (opts.mode === 'ecg') {
      const { control, data } = await ble.discoverPmd(peripheral);
      let lastPeak = null;
      const det = new QRSDetector({
        sampleRate: pmd.ECG_SAMPLE_RATE,
        onPeak: (t) => { if (lastPeak != null) accept(t - lastPeak); lastPeak = t; },
      });
      data.on('data', (buf) => { const p = pmd.parseEcg(buf); if (p) for (const s of p.samples) det.push(s); });
      await data.subscribeAsync();
      await control.writeAsync(pmd.ECG_START_COMMAND, false);
      log('ECG stream requested; collecting...');
      stop = async () => {
        try { await control.writeAsync(pmd.ECG_STOP_COMMAND, false); } catch (_) {}
        try { await data.unsubscribeAsync(); } catch (_) {}
      };
    } else {
      const { hrm } = await ble.discoverHr(peripheral);
      hrm.on('data', (buf) => { const { rr } = parseHrm(buf); for (const x of rr) accept(x); });
      await hrm.subscribeAsync();
      log('subscribed to HR Measurement; collecting...');
      stop = async () => { try { await hrm.unsubscribeAsync(); } catch (_) {} };
    }

    log(`capturing for ${opts.seconds}s from ${deviceName}`);
    await new Promise((r) => setTimeout(r, opts.seconds * 1000));
  } catch (e) {
    captureError = e;
  } finally {
    try { await stop(); } catch (_) {}
    try { await peripheral.disconnectAsync(); } catch (_) {}
  }

  if (captureError) {
    return finish({ ok: false, error: 'capture_failed', message: captureError.message, device: deviceName, mode: opts.mode, startedAt, finishedAt: new Date().toISOString(), samples: { rrAccepted: rrMs.length, beatsTotal: raw } }, 2);
  }

  const result = {
    ok: rrMs.length >= 2,
    device: deviceName,
    mode: opts.mode,
    startedAt,
    finishedAt: new Date().toISOString(),
    durationSec: opts.seconds,
    samples: { rrAccepted: rrMs.length, rrRejected: raw - rrMs.length, beatsTotal: raw },
    metrics: stats(rrMs),
  };
  if (opts.rr) result.rr_ms = rrMs.map((x) => Math.round(x));
  if (!result.ok) result.error = 'insufficient_data';

  finish(result, result.ok ? 0 : 1);
})().catch((e) => {
  process.stdout.write(JSON.stringify({ ok: false, error: 'fatal', message: e.message }) + '\n');
  process.exit(2);
});
