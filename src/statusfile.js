'use strict';

const fs = require('fs');
const path = require('path');
const { localIso } = require('./time');

/**
 * Writes the current vitals to a JSON file so external observers (e.g. Claude
 * Code reading the file, or a shell `cat`) can poll live values without a
 * network connection. Writes are atomic via a temp file + rename.
 */
class StatusFile {
  constructor(filePath) {
    this.filePath = filePath;
    this.tmpPath = filePath + '.tmp';
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
  }

  write(obj) {
    const payload = JSON.stringify({ ...obj, updatedAt: localIso() }, null, 2);
    fs.writeFileSync(this.tmpPath, payload);
    fs.renameSync(this.tmpPath, this.filePath);
  }
}

module.exports = { StatusFile };
