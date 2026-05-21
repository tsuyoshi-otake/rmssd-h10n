#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const pmd = require('./src/pmd');
const { parseHrm } = require('./src/hrm');
const { localIso } = require('./src/time');
const { Baseline, StateClassifier } = require('./src/analysis');
const { estimateRespiration } = require('./src/respiration');
const { median } = require('./src/rmssd');
const { QRSDetector } = require('./src/qrs');
const { RmssdWindow } = require('./src/rmssd');
const { CsvLogger } = require('./src/csv');
const { StatusFile } = require('./src/statusfile');
const { createServer } = require('./src/server');

function parseArgs(argv) {
  const opts = {
    port: 3000,
    window: 30,
    name: 'polar',
    csv: null,
    status: path.join(__dirname, 'data', 'status.json'),
    mode: 'hr-rr', // 'hr-rr' (default, robust) | 'ecg' (PMD raw, experimental) | 'simulate'
    server: true,
    scanTimeout: 30000,
    loadBaseline: false,
    baselineFile: path.join(__dirname, 'data', 'baseline.json'),
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case '--port': opts.port = Number(argv[++i]); break;
      case '--window': opts.window = Number(argv[++i]); break;
      case '--name': opts.name = argv[++i]; break;
      case '--csv': opts.csv = argv[++i]; break;
      case '--status': opts.status = argv[++i]; break;
      case '--simulate': opts.mode = 'simulate'; break;
      case '--ecg': opts.mode = 'ecg'; break;
      case '--no-server': opts.server = false; break;
      case '--load-baseline': opts.loadBaseline = true; break;
      case '--scan-timeout': opts.scanTimeout = Number(argv[++i]); break;
      case '--help': case '-h': printHelp(); process.exit(0); break;
      default: console.error(`Unknown argument: ${a}`); printHelp(); process.exit(1);
    }
  }
  if (!opts.csv) {
    const ts = localIso().replace(/[:.+]/g, '-');
    opts.csv = path.join(__dirname, 'data', `rmssd-${ts}.csv`);
  }
  return opts;
}

function printHelp() {
  console.log(`rmssd-h10n — Polar H10 real-time RMSSD (HRV) monitor

Usage: node index.js [options]

  (default)           use the standard HR service RR-intervals (robust on Windows)
  --ecg               use Polar PMD raw ECG + on-board QRS detection (experimental)
  --simulate          run with synthetic RR data, no Bluetooth hardware
  --port <n>          dashboard/API port (default 3000)
  --window <sec>      RMSSD sliding-window length in seconds (default 30)
  --name <str>        device name fragment to match (default "polar")
  --csv <path>        CSV output path (default data/rmssd-<timestamp>.csv)
  --status <path>     live status JSON path (default data/status.json)
  --no-server         disable the web dashboard / status API
  --load-baseline     reuse a saved resting baseline (data/baseline.json) if < 24 h old
  --scan-timeout <ms> BLE scan timeout (default 30000)
  -h, --help          show this help`);
}

async function main() {
  const opts = parseArgs(process.argv);
  const log = (...a) => console.log(`[${new Date().toLocaleTimeString()}]`, ...a);

  const rmssdWin = new RmssdWindow({ windowMs: opts.window * 1000 });
  const csv = new CsvLogger(opts.csv, ['wallClock', 'tMs', 'rr_ms', 'rmssd_ms', 'sdnn_ms', 'hr_bpm', 'rrCount', 'resp_brpm', 'resp_conf', 'corrected', 'state']);
  const statusFile = new StatusFile(opts.status);

  let connected = false;
  let lastPeakMs = null; // running session time in ms (sum of accepted/observed RR)
  let lastRr = null;
  let beats = 0;
  let deviceHr = null; // HR reported directly by the device (hr-rr mode)

  const baseline = new Baseline({ samples: 60 }); // ~first 60 s of settled readings
  const classifier = new StateClassifier({ minDwellMs: 45000 }); // hysteresis
  const respBuffer = []; // { tMs, rr } of artifact-cleaned NN for respiration (RSA)
  const RESP_WINDOW_MS = 120000;
  const respHistory = []; // recent { brpm, conf } estimates for temporal smoothing
  let baselineSaved = false;

  // Optionally reuse a recent resting baseline so the session starts calibrated.
  if (opts.loadBaseline) {
    try {
      const saved = JSON.parse(fs.readFileSync(opts.baselineFile, 'utf8'));
      if (saved && Date.now() - (saved.savedAt ?? 0) < 24 * 3600 * 1000) {
        baseline.loadFrozen(saved);
        baselineSaved = true;
        log(`Loaded saved baseline: RMSSD ${saved.rmssd} ms, HR ${saved.hr} bpm.`);
      }
    } catch (_) { /* no/invalid file -> calibrate fresh */ }
  }

  let server = null;
  if (opts.server) server = await createServer({ port: opts.port, log });

  // Dashboard "re-take resting baseline" button -> recalibrate from now.
  if (server) {
    server.events.on('baseline-reset', () => {
      baseline.reset();
      respHistory.length = 0;
      log('Baseline reset requested — recalibrating.');
    });
  }

  // Accept one RR interval (ms) observed at session time tMs. Only artifact-
  // accepted (NN) beats feed the respiration buffer, so a single ectopic/missed
  // beat cannot corrupt the RSA spectrum.
  function handleRR(tMs, rr) {
    beats++;
    lastRr = rr;
    const accepted = rmssdWin.add(tMs, rr);
    if (accepted) {
      respBuffer.push({ tMs, rr });
      const cutoff = tMs - RESP_WINDOW_MS;
      while (respBuffer.length && respBuffer[0].tMs < cutoff) respBuffer.shift();
    }
  }

  // 1 Hz reporting loop: compute window stats, publish to file/server/console/CSV.
  const reportTimer = setInterval(() => {
    const { rmssd, rmssdEma, hr, sdnn, count, corrected } = rmssdWin.compute(lastPeakMs ?? undefined);
    const wall = localIso();
    const effHr = deviceHr != null ? deviceHr : hr;

    const rmssdVal = rmssd != null ? Number(rmssd.toFixed(1)) : null;
    const rmssdSmoothed = rmssdEma != null ? Number(rmssdEma.toFixed(1)) : null;
    const hrVal = effHr != null ? Number(effHr.toFixed(1)) : null;

    // Baseline (settled readings) and autonomic-state estimate. The classifier
    // reads the SMOOTHED RMSSD so the label does not chase per-second noise.
    if (connected) {
      baseline.add(rmssdSmoothed, hrVal);
      if (baseline.get() && !baselineSaved) {
        try { fs.writeFileSync(opts.baselineFile, JSON.stringify(baseline.toJSON())); } catch (_) {}
        baselineSaved = true;
      }
    }
    const base = baseline.get();
    const state = classifier.update(rmssdSmoothed, hrVal, base, Date.now());

    // Respiration rate via RSA, smoothed across recent estimates (median of the
    // last few valid/preview windows) to suppress 1 Hz jitter.
    const resp = estimateRespiration(respBuffer);
    let respOut = null, respConf = null, respPreview = false;
    if (resp && (resp.valid || resp.preview)) {
      respHistory.push({ brpm: resp.breathsPerMin, conf: resp.confidence });
      if (respHistory.length > 5) respHistory.shift();
      respOut = Number(median(respHistory.map((e) => e.brpm)).toFixed(1));
      respConf = Number(median(respHistory.map((e) => e.conf)).toFixed(2));
      respPreview = resp.preview;
    } else {
      respHistory.length = 0;
    }

    const status = {
      connected,
      mode: opts.mode,
      hr: hrVal,
      rmssd: rmssdVal,
      rmssdSmoothed,
      sdnn: sdnn != null ? Number(sdnn.toFixed(1)) : null,
      rrCount: count,
      beatsTotal: beats,
      rejected: rmssdWin.rejected,
      corrected,
      baseline: base ? { rmssd: Number(base.rmssd.toFixed(1)), hr: Number(base.hr.toFixed(1)) } : null,
      calibration: Number(baseline.progress().toFixed(2)),
      state,
      respiration: respOut,
      respirationConfidence: respConf,
      respirationPreview: respPreview,
    };
    statusFile.write(status);
    if (server) {
      server.setStatus(status);
      server.pushPoint({ t: wall, rmssd: status.rmssd, hr: status.hr, resp: status.respiration, tone: state.tone });
    }
    csv.write({
      wallClock: wall,
      tMs: Math.round(lastPeakMs ?? 0),
      rr_ms: lastRr != null ? Math.round(lastRr) : '',
      rmssd_ms: status.rmssd ?? '',
      sdnn_ms: status.sdnn ?? '',
      hr_bpm: status.hr ?? '',
      rrCount: count,
      resp_brpm: status.respiration ?? '',
      resp_conf: status.respirationConfidence ?? '',
      corrected,
      state: state.label,
    });

    const r = status.rmssd != null ? `${status.rmssd} ms` : '–';
    const h = status.hr != null ? `${status.hr} bpm` : '–';
    const br = status.respiration != null ? `${status.respiration} br/min${respPreview ? '?' : ''}` : '–';
    log(`RMSSD ${r}  HR ${h}  resp ${br}  [${state.label}]  (RR ${count}, beats ${beats})`);
  }, 1000);

  // Cleanup handles populated by the active source.
  const cleanup = { fns: [] };

  // Register shutdown BEFORE any BLE work so Ctrl-C during scan/connect cleans up.
  let shuttingDown = false;
  const shutdown = async (code = 0) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log('Shutting down...');
    clearInterval(reportTimer);
    // Run cleanup LIFO: unsubscribe / send ECG-stop while still connected,
    // then disconnect, which was registered first.
    for (const fn of cleanup.fns.slice().reverse()) {
      try { await fn(); } catch (_) {}
    }
    await csv.close().catch(() => {});
    if (server) await server.close().catch(() => {});
    process.exit(code);
  };
  process.on('SIGINT', () => shutdown(0));
  process.on('SIGTERM', () => shutdown(0));

  try {
    await startMode();
  } catch (err) {
    console.error('Fatal:', err.message);
    await shutdown(1);
  }

  async function startMode() {
  if (opts.mode === 'simulate') {
    log('SIMULATE mode — generating synthetic RR intervals (no hardware).');
    connected = true;
    if (server) server.setStatus({ connected: true });
    let tMs = 0;
    const baseRr = 1000;
    let timer;
    const tick = () => {
      const resp = 40 * Math.sin((2 * Math.PI * tMs) / 5000);
      const noise = (Math.random() - 0.5) * 30;
      const rr = baseRr + resp + noise;
      tMs += rr;
      lastPeakMs = tMs;
      handleRR(tMs, rr);
      timer = setTimeout(tick, rr);
    };
    tick();
    cleanup.fns.push(() => clearTimeout(timer));
  } else if (opts.mode === 'ecg') {
    await runEcg(opts, log, cleanup, {
      onConnected: () => { connected = true; if (server) server.setStatus({ connected: true }); },
      onPeak: (peakMs) => {
        if (lastPeakMs != null) handleRR(peakMs, peakMs - lastPeakMs);
        lastPeakMs = peakMs;
      },
      onDisconnect: () => { log('Device disconnected.'); shutdown(1); },
    });
  } else {
    await runHrRr(opts, log, cleanup, {
      setConnected: (v) => {
        connected = v;
        if (!v) deviceHr = null; // clear stale HR while reconnecting
        if (server) server.setStatus({ connected: v });
      },
      onRr: (rr) => { lastPeakMs = (lastPeakMs ?? 0) + rr; handleRR(lastPeakMs, rr); },
      onHr: (hr) => { deviceHr = hr; },
    });
  }
  } // end startMode
}

// Default path: standard HR service (0x2A37) RR intervals, with auto-reconnect
// so the monitor survives the H10 dropping the BLE link mid-session.
async function runHrRr(opts, log, cleanup, { setConnected, onRr, onHr }) {
  const ble = require('./src/ble');
  let stopping = false;
  let current = null; // currently connected peripheral, if any

  // On shutdown: stop reconnecting and disconnect (timeout-guarded so an already
  // dropped device cannot hang the exit path).
  cleanup.fns.push(async () => {
    stopping = true;
    if (current) await ble.disconnectWithTimeout(current, 4000);
  });

  // Reject if a promise (e.g. a WinRT GATT op) does not settle in time.
  const withTimeout = (p, ms, what) =>
    Promise.race([
      p,
      new Promise((_, reject) => setTimeout(() => reject(new Error(`${what} timed out after ${ms} ms`)), ms)),
    ]);

  async function attach(peripheral) {
    current = peripheral;
    let live = false; // only handle drops AFTER a successful subscribe
    peripheral.once('disconnect', () => {
      current = null;
      if (!live || stopping) return;
      setConnected(false);
      log('Device disconnected — reconnecting...');
      connectLoop();
    });
    // Service discovery / subscribe can hang on WinRT after a flaky connect;
    // bound them so a stuck attach falls through to a clean retry.
    const { hrm } = await withTimeout(ble.discoverHr(peripheral), 10000, 'discoverHr');
    hrm.on('data', (buf) => {
      const { hr, rr } = parseHrm(buf);
      if (hr != null) onHr(hr);
      for (const interval of rr) onRr(interval);
    });
    await withTimeout(hrm.subscribeAsync(), 8000, 'subscribe');
    live = true;
    setConnected(true);
    log('Subscribed to HR Measurement (0x2A37). Reading RR intervals.');
  }

  async function connectLoop() {
    while (!stopping) {
      try {
        const peripheral = await ble.scanAndConnect({ nameMatch: opts.name, timeoutMs: opts.scanTimeout, log });
        if (stopping) { await ble.disconnectWithTimeout(peripheral, 4000); return; }
        await attach(peripheral);
        return; // connected; the disconnect handler re-enters connectLoop on drop
      } catch (e) {
        if (stopping) return;
        // Drop a half-open connection (e.g. connected but discover/subscribe hung)
        // before retrying, so the device is released for the next attempt.
        if (current) { await ble.disconnectWithTimeout(current, 3000); current = null; }
        log(`Connect/attach failed (${e.message}); retrying in 5s...`);
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
  }

  await connectLoop();
}

// Experimental path: Polar PMD raw ECG + local QRS detection.
async function runEcg(opts, log, cleanup, { onConnected, onPeak, onDisconnect }) {
  const ble = require('./src/ble');
  const peripheral = await ble.scanAndConnect({ nameMatch: opts.name, timeoutMs: opts.scanTimeout, log });
  peripheral.once('disconnect', onDisconnect);
  cleanup.fns.push(async () => { await ble.disconnectWithTimeout(peripheral, 4000); });

  const { control, data } = await ble.discoverPmd(peripheral);
  const detector = new QRSDetector({
    sampleRate: pmd.ECG_SAMPLE_RATE,
    onPeak,
  });
  data.on('data', (buf) => {
    const parsed = pmd.parseEcg(buf);
    if (parsed) for (const s of parsed.samples) detector.push(s);
  });
  await data.subscribeAsync();
  cleanup.fns.push(async () => {
    try { await control.writeAsync(pmd.ECG_STOP_COMMAND, false); } catch (_) {}
    try { await data.unsubscribeAsync(); } catch (_) {}
  });

  log('Requesting ECG stream (130 Hz, 14-bit)...');
  await control.writeAsync(pmd.ECG_START_COMMAND, false);
  onConnected();
  log('Streaming ECG. R-wave detection running.');
}

main().catch((err) => {
  console.error('Fatal:', err.message);
  process.exit(1);
});
