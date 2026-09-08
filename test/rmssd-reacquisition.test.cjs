'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { RmssdWindow } = require('../src/rmssd');

test('long gap expires old acceptance criteria in both directions (#18)', () => {
  for (const [oldRr, newRr] of [[1000, 600], [600, 1000]]) {
    const win = new RmssdWindow(); let t = 0;
    for (let i = 0; i < 20; i++) win.add(t += oldRr, oldRr);
    win.compute(t); t += 60000;
    assert.equal(win.compute(t).rmssdEma, null);
    for (let i = 0; i < 20; i++) assert.equal(win.add(t += newRr, newRr), true);
    assert.equal(win.compute(t).hr, 60000 / newRr);
    assert.equal(win.compute(t).rmssd, 0);
  }
});

test('five consecutive stable rejected beats reacquire without bridging old/new windows (#18)', () => {
  const win = new RmssdWindow({ windowMs: 300000 }); let t = 0;
  for (let i = 0; i < 20; i++) win.add(t += 1000, 1000);
  for (let i = 0; i < 4; i++) assert.equal(win.add(t += 600, 600), false);
  assert.equal(win.add(t += 600, 600), true);
  assert.equal(win.compute(t).count, 1);
  assert.equal(win.compute(t).rmssdEma, null);
  for (let i = 0; i < 10; i++) win.add(t += 600, 600);
  assert.equal(win.compute(t).rmssd, 0);
  assert.equal(win.rejected, 4);
});

test('isolated spikes and invalid values cannot replace the acceptance baseline (#18)', () => {
  const win = new RmssdWindow(); let t = 0;
  for (let i = 0; i < 20; i++) win.add(t += 800, 800);
  for (const rr of [1400, NaN, Infinity, 250, 2500]) {
    assert.equal(win.add(t += 100, rr), false);
    assert.equal(win.add(t += 800, 800), true);
  }
  assert.equal(win.add(Infinity, 1400), false);
  assert.equal(win.add(t += 800, 1400), false);
  assert.equal(win.compute(t).hr, 75);
  assert.equal(win.generation, 0);
});
