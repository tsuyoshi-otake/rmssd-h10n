'use strict';

const pmd = require('./pmd');
const { parseHrm } = require('./hrm');
const { QRSDetector } = require('./qrs');
const { localIso } = require('./time');
const { withDeadline, delay, abortError } = require('./async');

function stats(rr) {
  const count = rr.length;
  if (!count) return { count: 0, rmssd_ms: null, sdnn_ms: null, hr_bpm: null, meanRr_ms: null, minRr_ms: null, maxRr_ms: null };
  const mean = rr.reduce((a, b) => a + b, 0) / count;
  let differences = 0, variance = 0, min = Infinity, max = -Infinity;
  for (let i = 0; i < count; i++) {
    if (i) differences += (rr[i] - rr[i - 1]) ** 2;
    variance += (rr[i] - mean) ** 2;
    min = Math.min(min, rr[i]); max = Math.max(max, rr[i]);
  }
  const round = n => Number(n.toFixed(1));
  return { count, hr_bpm: round(60000 / mean), meanRr_ms: round(mean), minRr_ms: Math.round(min), maxRr_ms: Math.round(max),
    rmssd_ms: count < 2 ? null : round(Math.sqrt(differences / (count - 1))),
    sdnn_ms: count < 2 ? null : round(Math.sqrt(variance / count)) };
}

/** Every exit (including watchdog/signals) goes through the same bounded cleanup. */
async function measure(options, { ble = require('./ble'), signal: external, log = () => {},
  operationMs = 10000, cleanupMs = 2000, deadlineMs } = {}) {
  const opts = { seconds: 30, scanTimeout: 30000, name: 'polar', mode: 'hr-rr', ...options };
  if (!Number.isFinite(opts.seconds) || opts.seconds <= 0 || opts.seconds > 86400 ||
      !Number.isFinite(opts.scanTimeout) || opts.scanTimeout <= 0) throw new Error('Invalid capture duration or scan timeout');
  const startedAt = localIso();
  const controller = new AbortController();
  const signal = controller.signal;
  const cancel = () => controller.abort(abortError(external));
  external?.addEventListener('abort', cancel, { once: true });
  if (external?.aborted) cancel();
  const hardDeadlineMs = deadlineMs ?? opts.scanTimeout + opts.seconds * 1000 + 20000;
  const watchdog = setTimeout(() => controller.abort(Object.assign(new Error(`no result within ${hardDeadlineMs} ms`), { code: 'timeout' })), hardDeadlineMs);
  let peripheral, deviceName, onDisconnect, error, phase = 'connect', cleanupIncomplete = false;
  const cleanup = [], listeners = [], rrMs = [];
  let raw = 0, last = null;
  const accept = rr => {
    if (signal.aborted) return;
    raw++;
    if (!Number.isFinite(rr) || rr < 300 || rr > 2000) return;
    if (last != null && Math.abs(rr - last) / last > 0.3) return;
    rrMs.push(rr); last = rr;
  };
  try {
    peripheral = await ble.scanAndConnect({ nameMatch: opts.name, deviceId: opts.deviceId,
      timeoutMs: opts.scanTimeout, log, signal });
    if (signal.aborted) throw abortError(signal);
    deviceName = peripheral.advertisement?.localName || peripheral.id;
    phase = 'capture';
    onDisconnect = () => controller.abort(Object.assign(new Error('Device disconnected during capture'), { code: 'disconnected' }));
    peripheral.once('disconnect', onDisconnect);
    if (opts.mode === 'ecg') {
      const { control, data } = await withDeadline(() => ble.discoverPmd(peripheral), operationMs, 'discoverPmd', signal);
      let lastPeak = null;
      const detector = new QRSDetector({ sampleRate: pmd.ECG_SAMPLE_RATE,
        onPeak: t => { if (lastPeak != null) accept(t - lastPeak); lastPeak = t; } });
      const onData = buf => { if (signal.aborted) return; const frame = pmd.parseEcg(buf); if (frame) for (const sample of frame.samples) detector.push(sample); };
      data.on('data', onData); listeners.push([data, onData]);
      cleanup.push(() => data.unsubscribeAsync());
      await withDeadline(() => data.subscribeAsync(), operationMs, 'ECG subscribe', signal);
      cleanup.push(() => control.writeAsync(pmd.ECG_STOP_COMMAND, false));
      await withDeadline(() => control.writeAsync(pmd.ECG_START_COMMAND, false), operationMs, 'ECG start', signal);
    } else {
      const { hrm } = await withDeadline(() => ble.discoverHr(peripheral), operationMs, 'discoverHr', signal);
      const onData = buf => { if (!signal.aborted) for (const rr of parseHrm(buf).rr) accept(rr); };
      hrm.on('data', onData); listeners.push([hrm, onData]);
      cleanup.push(() => hrm.unsubscribeAsync());
      await withDeadline(() => hrm.subscribeAsync(), operationMs, 'subscribe', signal);
    }
    log(`capturing for ${opts.seconds}s from ${deviceName}`);
    await delay(opts.seconds * 1000, signal);
  } catch (cause) {
    error = cause;
  } finally {
    controller.abort();
    clearTimeout(watchdog);
    external?.removeEventListener('abort', cancel);
    if (peripheral && onDisconnect) peripheral.removeListener('disconnect', onDisconnect);
    for (const [characteristic, listener] of listeners) characteristic.removeListener('data', listener);
    for (const stop of cleanup.reverse()) {
      try { await withDeadline(stop, cleanupMs, 'capture cleanup'); }
      catch (_) { cleanupIncomplete = true; }
    }
    if (peripheral) {
      try { await withDeadline(() => ble.disconnectWithTimeout(peripheral), 4500, 'capture disconnect'); }
      catch (_) { cleanupIncomplete = true; }
    }
  }
  const result = { ok: !error && rrMs.length >= 2, device: deviceName, mode: opts.mode, startedAt,
    finishedAt: localIso(), durationSec: opts.seconds,
    samples: { rrAccepted: rrMs.length, rrRejected: raw - rrMs.length, beatsTotal: raw }, metrics: stats(rrMs) };
  if (cleanupIncomplete) result.cleanup = 'deadline_reached';
  if (opts.rr) result.rr_ms = rrMs.map(rr => Math.round(rr));
  if (error) {
    result.error = error.code === 'timeout' ? 'timeout' : error.name === 'AbortError' || error.code === 'interrupted' ? 'interrupted' : `${phase}_failed`;
    result.message = error.message;
  } else if (!result.ok) result.error = 'insufficient_data';
  return { result, code: error ? 2 : result.ok ? 0 : 1 };
}

module.exports = { measure, stats };
