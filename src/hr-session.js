'use strict';

const { parseHrm } = require('./hrm');
const { withDeadline, delay, untilAborted, retryDelay } = require('./async');

/** One owner drives scan → attach → live → cleanup → retry. Callbacks only cancel it. */
class HrSession {
  constructor({ ble = require('./ble'), name = 'polar', deviceId, scanTimeout = 30000,
    log = () => {}, setConnected = () => {}, onRr = () => {}, onHr = () => {}, onBattery,
    onStage = () => {}, now = Date.now, random = Math.random,
    discoverMs = 10000, subscribeMs = 8000, cleanupMs = 2000, stallMs = 20000, retryMs = 5000 } = {}) {
    Object.assign(this, { ble, name, deviceId, scanTimeout, log, setConnected, onRr, onHr, onBattery,
      onStage, now, random, discoverMs, subscribeMs, cleanupMs, stallMs, retryMs });
    this.stopSignal = new AbortController();
    this.ready = new Promise(resolve => { this.resolveReady = resolve; });
  }

  start() {
    if (!this.done) this.done = this.run().finally(() => this.resolveReady());
    return this;
  }

  async stop() {
    this.onStage('stopping', 'user_stopped');
    this.stopSignal.abort();
    await this.done;
  }

  async run() {
    let attempt = 0;
    const root = this.stopSignal.signal;
    while (!root.aborted) {
      const scope = new AbortController();
      const cancel = () => scope.abort(root.reason);
      root.addEventListener('abort', cancel, { once: true });
      const signal = scope.signal;
      let peripheral, onDisconnect, watchdog;
      const subscriptions = [];
      let failure;
      const valid = () => !signal.aborted && !root.aborted;
      try {
        peripheral = await this.ble.scanAndConnect({ nameMatch: this.name, deviceId: this.deviceId,
          timeoutMs: this.scanTimeout, log: this.log, signal,
          onStage: stage => { if (valid()) this.onStage(stage, stage, { attempt }); } });
        if (!valid()) throw new Error('Cancelled');
        onDisconnect = () => scope.abort(Object.assign(new Error('Device disconnected'), { code: 'disconnected' }));
        peripheral.once('disconnect', onDisconnect);
        this.onStage('subscribing', 'discover_hr', { attempt });
        const { hrm } = await withDeadline(() => this.ble.discoverHr(peripheral), this.discoverMs, 'discoverHr', signal);
        let lastDataAt = this.now();
        const onData = buf => {
          if (!valid()) return;
          const { hr, rr } = parseHrm(buf);
          lastDataAt = this.now();
          if (hr != null) this.onHr(hr);
          for (const interval of rr) this.onRr(interval);
        };
        subscriptions.push({ characteristic: hrm, listener: onData });
        hrm.on('data', onData);
        await withDeadline(() => hrm.subscribeAsync(), this.subscribeMs, 'subscribe', signal);
        if (!valid()) throw new Error('Cancelled');
        this.setConnected(true);
        this.onStage('streaming', 'hr_ready');
        this.resolveReady();
        this.log('Subscribed to HR Measurement (0x2A37). Reading RR intervals.');
        watchdog = setInterval(() => {
          if (valid() && this.now() - lastDataAt >= this.stallMs) {
            scope.abort(Object.assign(new Error('HR notifications stopped'), { code: 'stream_stalled' }));
          }
        }, Math.min(5000, this.stallMs));
        const resetAttempt = () => { if (valid()) attempt = 0; };
        hrm.on('data', resetAttempt);
        subscriptions.push({ characteristic: hrm, listener: resetAttempt, listenerOnly: true });

        if (this.onBattery) {
          try {
            const battery = await withDeadline(() => this.ble.discoverBattery(peripheral), 6000, 'discoverBattery', signal);
            if (battery && valid()) {
              const apply = buf => { if (valid() && buf?.length) this.onBattery(buf.readUInt8(0)); };
              subscriptions.push({ characteristic: battery, listener: apply });
              battery.on('data', apply);
              try { apply(await withDeadline(() => battery.readAsync(), 5000, 'battery read', signal)); } catch (_) {}
              await withDeadline(() => battery.subscribeAsync(), 5000, 'battery subscribe', signal);
            }
          } catch (_) { /* Optional battery failure leaves HR streaming. */ }
        }
        await untilAborted(signal);
        failure = signal.reason;
      } catch (error) {
        failure = error;
      } finally {
        scope.abort();
        clearInterval(watchdog);
        this.setConnected(false);
        if (peripheral && onDisconnect) peripheral.removeListener('disconnect', onDisconnect);
        for (const { characteristic, listener } of subscriptions) characteristic.removeListener('data', listener);
        for (const { characteristic, listenerOnly } of subscriptions) {
          if (!listenerOnly) {
            try { await withDeadline(() => characteristic.unsubscribeAsync(), this.cleanupMs, 'unsubscribe'); } catch (_) {}
          }
        }
        if (peripheral) await this.ble.disconnectWithTimeout(peripheral);
        root.removeEventListener('abort', cancel);
      }
      if (!root.aborted) {
        const waitMs = retryDelay(++attempt, this.random, this.retryMs);
        this.onStage('retry_wait', failure?.code || 'connection_failed', { attempt, nextRetryAt: this.now() + waitMs });
        this.log(`Connection ended (${failure?.message || 'unknown'}); retrying in ${Math.ceil(waitMs / 1000)}s...`);
        try { await delay(waitMs, root); } catch (_) {}
      }
    }
    this.onStage('stopped', 'user_stopped');
  }
}

module.exports = { HrSession };
