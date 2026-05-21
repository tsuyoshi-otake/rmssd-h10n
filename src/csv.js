'use strict';

const fs = require('fs');
const path = require('path');

/**
 * Append-only CSV logger. Writes a header on first open and flushes each row.
 */
class CsvLogger {
  /**
   * @param {string} filePath destination CSV path
   * @param {string[]} columns column headers
   */
  constructor(filePath, columns) {
    this.filePath = filePath;
    this.columns = columns;
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    const fresh = !fs.existsSync(filePath) || fs.statSync(filePath).size === 0;
    this.stream = fs.createWriteStream(filePath, { flags: 'a' });
    // Surface disk/path errors instead of crashing the process asynchronously.
    this.stream.on('error', (err) => console.error(`CSV write error (${filePath}):`, err.message));
    if (fresh) this.stream.write(columns.join(',') + '\n');
  }

  /**
   * @param {Record<string, unknown>} row keyed by column name
   */
  write(row) {
    const line = this.columns
      .map((c) => {
        const v = row[c];
        if (v == null) return '';
        const s = String(v);
        return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
      })
      .join(',');
    this.stream.write(line + '\n');
  }

  close() {
    return new Promise((resolve) => this.stream.end(resolve));
  }
}

module.exports = { CsvLogger };
