'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { RmssdWindow, median } = require('../src/rmssd');
const { Baseline, StateClassifier } = require('../src/analysis');
const { estimateRespiration } = require('../src/respiration');
const { SampleFreshness } = require('../src/sample-freshness');
const { ConnectionStatus } = require('../src/connection-status');
const { localIso } = require('../src/time');

test('real report callback does not write held values after disconnect or silence (#19)', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'index.js'), 'utf8');
  const start = source.indexOf('  const reportTimer = setInterval(() => {');
  const end = source.indexOf('  }, 1000);', start) + '  }, 1000);'.length;
  for (const connected of [false, true]) {
    let mono = 0, wall = 1788822000000;
    const freshness = new SampleFreshness({ now: () => mono, wall: () => wall });
    const win = new RmssdWindow(); let peak = 0;
    for (let i = 0; i < 40; i++) { const rr = i % 2 ? 990 : 1010; freshness.receiveRr(rr); win.add(peak += rr, rr); freshness.acceptRr(); }
    freshness.receiveHr(60);
    const points = [], rows = [], statuses = []; let report;
    const ctx = vm.createContext({
      setInterval: fn => { report = fn; }, rmssdWin: win, lastPeakMs: peak, freshness,
      localIso, deviceHr: 60, connected, currentUser: 1, deviceBattery: 90, beats: 40, lastRr: 990,
      opts: { mode: 'hr-rr' }, connection: new ConnectionStatus(),
      baseline: new Baseline({ samples: 60 }), baselineSaved: false, lastAdaptedAt: null,
      classifier: new StateClassifier({ minDwellMs: 45000 }), estimateRespiration, median,
      respBuffer: [], respHistory: [], statusFile: { write: s => statuses.push(s) },
      server: { setStatus() {}, pushPoint: p => points.push(p) }, csv: { write: r => rows.push(r) }, log() {},
    });
    vm.runInContext(source.slice(start, end), ctx, { timeout: 1000 });
    mono = 6000;
    for (let i = 0; i < 60; i++) { mono += 1000; wall += 1000; report(); }
    assert.equal(points.length, 0);
    assert.equal(rows.length, 0);
    assert.ok(statuses.every(s => s.dataFresh === false && s.hr === null && s.rmssd === null && s.rmssdSmoothed === null));
    assert.equal(statuses.at(-1).sampleAgeMs, 66000);
  }
});
