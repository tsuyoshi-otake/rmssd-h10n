'use strict';

const { localIso } = require('./time');

/** Small diagnostic history; never contains RR samples or full device identifiers. */
class ConnectionStatus {
  constructor({ now = Date.now, limit = 64 } = {}) {
    this.now = now;
    this.limit = limit;
    this.events = [];
    this.transition('idle', 'not_started');
  }

  transition(stage, reason = stage, { attempt = 0, nextRetryAt = null } = {}) {
    stage = clean(stage, 'idle');
    reason = clean(reason, 'connection_failed');
    const previous = this.current;
    if (previous?.stage === stage && previous.reason === reason && previous.attempt === attempt && previous.nextRetryAt === nextRetryAt) return;
    this.current = { stage, reason, attempt, nextRetryAt, changedAt: this.now() };
    this.events.push({ ...this.current });
    if (this.events.length > this.limit) this.events.splice(0, this.events.length - this.limit);
  }

  snapshot() {
    return serialize(this.current);
  }
  diagnostics() { return { exportedAt: localIso(), connection: this.snapshot(), events: this.events.map(serialize) }; }
}

function clean(value, fallback) {
  return typeof value === 'string' && /^[a-z0-9_]{1,48}$/.test(value) ? value : fallback;
}

function serialize(value) {
  return {
    ...value,
    changedAt: localIso(new Date(value.changedAt)),
    nextRetryAt: value.nextRetryAt == null ? null : localIso(new Date(value.nextRetryAt)),
  };
}

module.exports = { ConnectionStatus };
