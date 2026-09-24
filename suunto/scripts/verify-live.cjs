'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

// The SDK rewrites callbacks and output access; test the binary's dispatcher too.
module.exports = function verifyLive(main, screenFormats) {
  function load(unit = '0') {
    let utc = 1788632316;
    let handler;
    const context = vm.createContext({
      enabledZappId: 42,
      localStorage: { getItem: () => unit },
      $: { get: (resource, callback) => {
        assert.equal(resource, '/Dev/Time');
        callback(utc);
      } },
      appConn: {
        connect: (id, callback) => { handler = callback; return 11; },
        regUuid: () => {},
        enaCharNotf: () => {},
        readChar: () => {}
      }
    });
    const dispatch = vm.runInContext(`(function () { ${main}\n})()`, context);
    const values = new Float32Array([0, -1, 32768, -1, 6]);
    function tick(second, event = 1) {
      utc = 1788632316 + second;
      dispatch(event, values);
      return values;
    }
    function send(fields, second) {
      tick(second);
      const bytes = Buffer.from('020200007e0032001e0000000700000009000000', 'hex');
      for (const [name, value] of Object.entries(fields)) {
        if (name === 'version') bytes[0] = value;
        else if (name === 'flags') bytes[1] = value;
        else if (name === 'status') bytes[2] = value;
        else if (name === 'reserved') bytes[3] = value;
        else if (name === 'glucose') bytes.writeUInt16LE(value, 4);
        else if (name === 'trend') bytes.writeInt16LE(value, 6);
        else if (name === 'age') bytes.writeUInt32LE(value, 8);
        else if (name === 'sample') bytes.writeUInt32LE(value, 12);
        else if (name === 'sequence') bytes.writeUInt32LE(value, 16);
        else throw new Error(name);
      }
      handler(0, 106, bytes);
      return tick(second);
    }
    function reconnect(second) {
      handler(0, 101);
      handler(0, 100);
      tick(second);
      handler(0, 109);
      tick(second + 1);
    }
    dispatch(2, values);
    tick(0);
    handler(0, 100);
    tick(1);
    handler(0, 107);
    tick(2);
    handler(0, 109);
    tick(3);
    return { tick, send, reconnect, dispatch, values, event: event => handler(0, event) };
  }
  const outage = { status: 1, glucose: 65535, trend: -32768, age: 0xffffffff, sample: 0 };
  function hidden(values, status) {
    assert.equal(values[1], -1);
    assert.equal(values[2], 32768);
    assert.equal(values[4], status);
  }

  for (const unit of ['0', '1']) {
    const h = load(unit);
    h.send({}, 100);
    assert.equal(h.values[1], unit === '0' ? 7 : 126);
    assert.equal(h.values[2], Math.fround(unit === '0' ? 0.03 : 0.5));
    assert.equal(h.dispatch(4096, h.values).template, unit === '0' ? 'mmol' : 'mgdl');
    assert.equal(h.dispatch(8192, h.values).length, 0);
    h.send({ ...outage, sequence: 10 }, 105);
    hidden(h.values, 1);
    h.reconnect(108);
    h.send({ age: 0, sequence: 0, glucose: 127 }, 110);
    hidden(h.values, 8);
    h.send({ age: 0, sequence: 0 }, 111);
    assert.equal(h.values[1], unit === '0' ? 7 : 126);
    assert.equal(h.values[3], 41, 'outage/restart preserves original age');
    h.send({ age: 0, sequence: 0 }, 125);
    h.tick(131, 256);
    hidden(h.values, 7);
    h.tick(132, 512);
    hidden(h.values, 7);
    h.send({ sample: 8, sequence: 1, age: 0, trend: -32768 }, 133);
    assert.equal(h.values[1], unit === '0' ? 7 : 126);
    assert.equal(h.values[2], 32768);
    h.send({ sample: 8, sequence: 2, trend: 50 }, 134);
    hidden(h.values, 8);
    h.send({ sample: 7, sequence: 2 }, 135);
    hidden(h.values, 8);
    h.send({ sample: 9, sequence: 2 }, 136);
    assert.equal(h.values[4], 0);
    h.tick(135);
    hidden(h.values, 12);
    assert.equal(h.values[3], -1);
    h.reconnect(140);
    h.send({ sample: 10, sequence: 0 }, 142);
    hidden(h.values, 12);
  }

  for (const unit of ['0', '1']) {
    const formats = screenFormats.get(unit);
    const label = unit === '0' ? 'mmol/L/min' : 'mg/dL/min';
    for (const [trend, mmol, mgdl] of [
      [50, '+0.03', '+0.50'], [-50, '-0.03', '-0.50'], [0, '0.00', '0.00'],
      [1, '0.00', '+0.01'], [-1, '0.00', '-0.01'],
      [9, '+0.01', '+0.09'], [-9, '-0.01', '-0.09'],
      [32767, '+18.20', '+327.67'], [-32767, '-18.20', '-327.67'],
      [-32768, '--', '--']
    ]) {
      const h = load(unit);
      h.send({ trend }, 100);
      assert.equal(h.values[4], 0);
      assert.equal(formats.get('glucose')(h.values[1]), unit === '0' ? '7.0' : '126');
      assert.equal(formats.get('trend')(h.values[2]), `${unit === '0' ? mmol : mgdl} ${label}`);
      h.tick(120);
      hidden(h.values, 7);
      assert.equal(formats.get('trend')(h.values[2]), `-- ${label}`);
    }
  }

  const age = load();
  age.send({ age: 590 }, 100);
  age.send({ ...outage, sequence: 10 }, 105);
  age.reconnect(106);
  age.send({ age: 0, sequence: 0 }, 109);
  assert.equal(age.values[3], 599);
  assert.equal(age.values[4], 0);
  age.tick(109.999);
  assert.equal(age.values[4], 0);
  age.tick(110);
  hidden(age.values, 5);
  assert.equal(age.values[3], 600);

  const heartbeat = load();
  heartbeat.send({}, 100);
  heartbeat.tick(119.999);
  assert.equal(heartbeat.values[4], 0);
  heartbeat.tick(120);
  hidden(heartbeat.values, 7);

  const wrap = load();
  wrap.send({ sequence: 0xffffffff }, 100);
  wrap.send({ sequence: 0 }, 105);
  assert.equal(wrap.values[4], 0);
  wrap.send({ sequence: 0x80000000 }, 110);
  wrap.send({ sequence: 0xffffffff }, 111);
  wrap.send({ sequence: 0 }, 112);
  wrap.tick(125);
  hidden(wrap.values, 7);
  wrap.event(100);
  wrap.tick(126);
  wrap.event(109);
  wrap.tick(127);
  wrap.send({ sequence: 0xffffffff, sample: 8 }, 128);
  hidden(wrap.values, 7);
  wrap.send({ sequence: 1, sample: 8 }, 129);
  assert.equal(wrap.values[4], 0);

  for (let status = 1; status <= 5; status++) {
    const h = load();
    h.send({}, 100);
    h.send({ ...outage, sample: 7, status, sequence: 10 }, 105);
    hidden(h.values, status);
    h.send({ glucose: 127, sequence: 11 }, 106);
    hidden(h.values, 8);
  }
  for (const fields of [
    { version: 1, flags: 1 }, { flags: 0 }, { flags: 1 }, { flags: 3 }, { version: 3 },
    { reserved: 1 }, { status: 6 }, { sample: 0 }, { glucose: 0 }, { glucose: 65535 },
    { age: 600 }, { age: 0xffffffff }, { status: 1 }, { ...outage, trend: 50 }
  ]) {
    const h = load();
    h.send({}, 100);
    h.send({ ...fields, sequence: 10 }, 105);
    hidden(h.values, 8);
  }
  console.log('Built LIVE callbacks: sentinels, profile rejection, age/heartbeat edges, outage/reconnect/history, epoch order, sequence wrap, clock latch passed.');
};
