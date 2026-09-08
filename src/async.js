'use strict';

function abortError(signal) {
  return signal?.reason instanceof Error ? signal.reason : Object.assign(new Error('Cancelled'), { code: 'cancelled' });
}

/** A deadline owns its timer/listener even when the underlying native call never settles. */
function withDeadline(work, ms, what = 'operation', signal) {
  if (signal?.aborted) return Promise.reject(abortError(signal));
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
      fn(value);
    };
    const cancel = () => finish(reject, abortError(signal));
    const timer = setTimeout(() => finish(reject,
      Object.assign(new Error(`${what} timed out after ${ms} ms`), { code: 'timeout' })), ms);
    signal?.addEventListener('abort', cancel, { once: true });
    Promise.resolve().then(() => {
      if (signal?.aborted) throw abortError(signal);
      return typeof work === 'function' ? work() : work;
    }).then(value => finish(resolve, value), error => finish(reject, error));
  });
}

function delay(ms, signal) {
  if (signal?.aborted) return Promise.reject(abortError(signal));
  return new Promise((resolve, reject) => {
    const cancel = () => { clearTimeout(timer); reject(abortError(signal)); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', cancel); resolve(); }, ms);
    signal?.addEventListener('abort', cancel, { once: true });
  });
}

function untilAborted(signal) {
  if (signal.aborted) return Promise.resolve();
  return new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }));
}

function retryDelay(attempt, random = Math.random, base = 5000, cap = 60000) {
  const ceiling = Math.min(cap, base * 2 ** Math.min(10, Math.max(0, attempt - 1)));
  return Math.round(ceiling * (0.8 + random() * 0.2));
}

module.exports = { abortError, withDeadline, delay, untilAborted, retryDelay };
