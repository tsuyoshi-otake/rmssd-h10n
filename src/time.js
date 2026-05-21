'use strict';

// ISO-8601 timestamp in the machine's LOCAL time zone, with explicit offset,
// e.g. "2026-05-21T21:27:13.888+09:00" for JST. Unambiguous and human-readable
// in local time — unlike toISOString(), which is always UTC ("...Z").
function localIso(d = new Date()) {
  const offMin = -d.getTimezoneOffset(); // JST -> +540
  const sign = offMin >= 0 ? '+' : '-';
  const pad = (n, w = 2) => String(Math.abs(n)).padStart(w, '0');
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  const body = local.toISOString().slice(0, -1); // drop trailing 'Z'
  return `${body}${sign}${pad(offMin / 60 | 0)}:${pad(offMin % 60)}`;
}

module.exports = { localIso };
