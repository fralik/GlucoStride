'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const vm = require('node:vm');
const { readProfile } = require('../scripts/profiles.cjs');

const root = path.resolve(__dirname, '..');
const files = readProfile(root, 'demo');
const source = files['main.js'];
const canonical = Buffer.from('010100007e0032001e0000000700000009000000', 'hex');

function load(unit = '0') {
  const calls = [];
  const clock = { now: undefined, deferred: false, pending: [], reads: 0 };
  const context = vm.createContext({
    localStorage: { getItem: () => unit },
    enabledZappId: 42,
    $: {
      get: (resource, callback) => {
        assert.equal(resource, '/Dev/Time');
        clock.reads++;
        if (clock.deferred) clock.pending.push(callback);
        else callback(clock.now);
      }
    },
    appConn: Object.fromEntries(['connect', 'regUuid', 'enaCharNotf', 'readChar'].map(name => [
      name, (...args) => { calls.push({ name, args }); return 11; }
    ]))
  });
  vm.runInContext(source, context);
  const tick = (now, output, event = 'evaluate') => {
    clock.now = now;
    // A cached/rounded epoch value must not be used as the receiver's clock.
    context[event]({ utc: Math.fround(now) }, output);
  };
  return { context, calls, clock, tick };
}

function packet(fields = {}) {
  const bytes = Buffer.from(canonical);
  for (const [field, value] of Object.entries(fields)) {
    if (field === 'version') bytes[0] = value;
    else if (field === 'flags') bytes[1] = value;
    else if (field === 'status') bytes[2] = value;
    else if (field === 'reserved') bytes[3] = value;
    else if (field === 'glucose') bytes.writeUInt16LE(value, 4);
    else if (field === 'trend') bytes.writeInt16LE(value, 6);
    else if (field === 'age') bytes.writeUInt32LE(value, 8);
    else if (field === 'sample') bytes.writeUInt32LE(value, 12);
    else if (field === 'sequence') bytes.writeUInt32LE(value, 16);
    else throw new Error(field);
  }
  return bytes;
}

function receiver() {
  const r = load().context.createReceiver();
  r.setConnected(true);
  return r;
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test('canonical 20-byte little-endian fixture and default mmol conversion', () => {
  const { context: c } = load();
  assert.deepEqual(plain(c.decodePacket(canonical)), {
    status: 0, glucose: 126, trend: 0.5, age: 30, sample: 7, sequence: 9
  });
  assert.equal(c.convertGlucose(126, 0), 7);
  assert.equal(c.convertGlucose(126, 1), 126);
  assert.equal(c.convertGlucose(null, 0), -1);
  assert.equal(c.convertGlucose(127, 0).toFixed(1), '7.1');
});

test('rejects malformed length, byte values, flags, status, reserved and version', () => {
  const { context: c } = load();
  for (const bytes of [
    null, undefined, '', canonical.toString('hex'), [], canonical.subarray(0, 19),
    Buffer.concat([canonical, Buffer.from([0])]), packet({ flags: 0 }), packet({ flags: 3 }),
    packet({ flags: 255 }), packet({ status: 6 }), packet({ reserved: 1 }), packet({ version: 2 }),
    [1.1, ...canonical.subarray(1)], [NaN, ...canonical.subarray(1)],
    [256, ...canonical.subarray(1)], [-1, ...canonical.subarray(1)],
    ['1', ...canonical.subarray(1)]
  ]) assert.equal(c.decodePacket(bytes), null);
});

test('strict status/sentinel validation; no clinical thresholds inferred', () => {
  const { context: c } = load();
  assert.deepEqual(plain(c.decodePacket(Buffer.from('01010200ffff0080ffffffff070000000a000000', 'hex'))), {
    status: 2, glucose: null, trend: null, age: null, sample: 7, sequence: 10
  });
  for (let status = 1; status <= 5; status++) {
    const valid = packet({ status, glucose: 65535, trend: -32768, age: status === 5 ? 600 : 0xffffffff });
    assert.equal(c.decodePacket(valid).status, status);
    assert.equal(c.decodePacket(valid).glucose, null);
    assert.equal(c.decodePacket(valid).trend, null);
    assert.equal(c.decodePacket(packet({ status })), null);
  }
  assert.equal(c.decodePacket(packet({ glucose: 65535 })), null);
  assert.equal(c.decodePacket(packet({ age: 0xffffffff })), null);
  assert.equal(c.decodePacket(packet({ age: 600 })), null);
  assert.equal(c.decodePacket(packet({ glucose: 0 })), null);
  assert.equal(c.decodePacket(packet({ glucose: 65534 })).glucose, 65534);
  assert.equal(c.decodePacket(packet({ trend: -32768 })).trend, null);
  assert.equal(c.decodePacket(packet({ trend: -32767 })).trend, -327.67);
  assert.equal(c.decodePacket(packet({ trend: 32767 })).trend, 327.67);
});

test('age advances without packets and expires at exact 600-second boundary', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  assert.equal(r.snapshot(109.999).status, 0);
  const view = r.snapshot(110);
  assert.equal(view.status, 5);
  assert.equal(view.age, 600);
  assert.equal(view.glucose, null);
  assert.equal(view.trend, null);
});

test('heartbeat expires exactly at 20 seconds, independent of sample age', () => {
  const r = receiver();
  r.receive(canonical, 100);
  assert.equal(r.snapshot(119.999).status, 0);
  assert.equal(r.snapshot(120).status, 7);
  assert.equal(r.snapshot(120).glucose, null);
  assert.equal(r.snapshot(140).age, 70);
});

test('same sample never gets younger across heartbeats or reconnects', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  r.receive(packet({ age: 1, sequence: 10 }), 105);
  assert.equal(r.snapshot(105).age, 595);
  r.setConnected(false);
  r.snapshot(107);
  r.setConnected(true);
  r.receive(packet({ age: 0, sequence: 11 }), 110);
  assert.equal(r.snapshot(110).age, 600);
  assert.equal(r.snapshot(110).status, 5);
  r.receive(packet({ age: 0, sample: 8, sequence: 12 }), 115);
  assert.equal(r.snapshot(115).status, 0);
  assert.equal(r.snapshot(115).age, 0);
});

test('source age can increase and fractional elapsed time is not discarded', () => {
  const r = receiver();
  r.receive(canonical, 0);
  r.receive(packet({ age: 50, sequence: 10 }), 0.25);
  r.receive(packet({ age: 0, sequence: 11 }), 0.75);
  assert.equal(r.snapshot(1).age, 50.75);
});

test('duplicates/out-of-order packets do not keep heartbeat alive, including reconnect', () => {
  const r = receiver();
  r.receive(canonical, 100);
  for (let now = 101; now <= 125; now++) {
    assert.equal(r.receive(packet({ sequence: now % 2 ? 9 : 8 }), now), false);
  }
  assert.equal(r.snapshot(125).status, 7);
  assert.equal(r.lastRx, 100);
  r.setConnected(false);
  r.setConnected(true);
  assert.equal(r.receive(canonical, 126), false);
  assert.equal(r.snapshot(126).glucose, null);
  r.receive(packet({ sequence: 10 }), 127);
  assert.equal(r.snapshot(127).status, 0);
  assert.equal(r.snapshot(127).age, 57);
});

test('uint32 sequence wrap is accepted; ambiguous half-range is rejected', () => {
  const r = receiver();
  r.receive(packet({ sequence: 0xffffffff }), 0);
  assert.equal(r.receive(packet({ sequence: 0 }), 5), true);
  assert.equal(r.receive(packet({ sequence: 0xffffffff }), 10), false);
  assert.equal(r.receive(packet({ sequence: 0x80000000 }), 15), false);
  assert.equal(r.snapshot(25).status, 7);
});

test('phone restart permits a new random sequence baseline only after disconnect with a different sample', () => {
  for (const sequence of [1, 9, 0xffffffff, 0x80000009]) {
    const r = receiver();
    r.receive(canonical, 100);
    r.setConnected(false);
    r.snapshot(110);
    r.setConnected(true);
    assert.equal(r.receive(packet({ sequence, sample: 0xf0000000, age: 0 }), 111), true);
    assert.equal(r.snapshot(111).status, 0);
    assert.equal(r.snapshot(111).age, 0);
    assert.equal(r.sequence, sequence);
    assert.equal(r.receive(packet({ sequence, sample: 0xf0000000, age: 0 }), 125), false);
    assert.equal(r.snapshot(131).status, 7);
  }
});

test('changed sample or elapsed heartbeat alone cannot authorize sequence rebase', () => {
  const r = receiver();
  r.receive(canonical, 100);
  assert.equal(r.receive(packet({ sequence: 1, sample: 8 }), 110), false);
  r.setConnected(true); // A repeated CONNECTED event without disconnection is not a restart.
  assert.equal(r.receive(packet({ sequence: 1, sample: 8 }), 125), false);
  assert.equal(r.snapshot(125).glucose, null);
});

test('same-sample reconnect rejects replay and retains age; first accepted packet closes rebase window', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  r.setConnected(false);
  r.setConnected(true);
  assert.equal(r.receive(packet({ age: 0, sequence: 1 }), 105), false);
  assert.equal(r.receive(packet({ age: 0, sequence: 9 }), 106), false);
  assert.equal(r.receive(packet({ age: 0, sequence: 10 }), 110), true);
  assert.equal(r.snapshot(110).age, 600);
  assert.equal(r.snapshot(110).status, 5);
  assert.equal(r.receive(packet({ sample: 8, sequence: 1 }), 111), false);
});

test('malformed/non-demo packets suppress immediately and duplicates cannot repair them', () => {
  const r = receiver();
  r.receive(canonical, 100);
  r.receive(packet({ flags: 0, sequence: 10 }), 101);
  assert.equal(r.snapshot(101).status, 8);
  assert.equal(r.snapshot(101).glucose, null);
  r.receive(canonical, 102);
  assert.equal(r.snapshot(102).status, 8);
  r.receive(packet({ sequence: 10 }), 103);
  assert.equal(r.snapshot(103).status, 0);
});

test('same sample cannot silently change its glucose or rate', () => {
  const r = receiver();
  r.receive(canonical, 0);
  assert.equal(r.receive(packet({ glucose: 127, sequence: 10 }), 5), false);
  assert.equal(r.snapshot(5).status, 8);
  assert.equal(r.receive(packet({ trend: 100, sequence: 10 }), 6), false);
});

test('missing, non-finite, or regressing clock latches fail closed', () => {
  for (const time of [99, NaN, Infinity, undefined, null, '100', -1]) {
    const r = receiver();
    r.receive(canonical, 100);
    assert.equal(r.snapshot(time).status, 9);
    assert.equal(r.receive(packet({ sequence: 10 }), 200), false);
    assert.equal(r.snapshot(200).glucose, null);
    assert.equal(r.snapshot(200).age, null);
  }
});

test('unavailable, warmup, source error and unknown age never show glucose', () => {
  const r = receiver();
  for (let status = 1; status <= 5; status++) {
    r.receive(packet({ status, glucose: 65535, trend: -32768, age: 0xffffffff, sequence: 10 + status }), status * 5);
    assert.equal(r.snapshot(status * 5).status, status);
    assert.equal(r.snapshot(status * 5).glucose, null);
    assert.equal(r.snapshot(status * 5).trend, null);
  }
});

test('official BLE callback state machine registers, subscribes and reads custom characteristic', () => {
  const { context: c, calls, tick } = load();
  const output = {};
  c.onLoad({}, output);
  assert.equal(output.status, 6);
  tick(100, output);
  assert.equal(calls[0].name, 'connect');
  assert.deepEqual(calls[0].args.slice(0, 1), [42]);
  assert.deepEqual(Array.from(calls[0].args[2]), [6, ...c.serviceUuid]);
  assert.deepEqual(Array.from(calls[0].args[3]), [7, ...c.serviceUuid]);
  assert.equal(Buffer.from(c.serviceUuid).reverse().toString('hex'), '7b9e10006d8b4f3a9c212e8a6f0d5b47');
  assert.equal(Buffer.from(c.characteristicUuid).reverse().toString('hex'), '7b9e10016d8b4f3a9c212e8a6f0d5b47');
  c.bleEventHandler(0, 111);
  tick(101, output);
  assert.equal(calls.length, 1, 'CONNECT_DONE is not CONNECTED');
  c.bleEventHandler(0, 100);
  tick(102, output);
  assert.equal(calls[1].name, 'regUuid');
  c.bleEventHandler(0, 107);
  tick(103, output);
  assert.equal(calls[2].name, 'enaCharNotf');
  c.bleEventHandler(0, 109);
  tick(104, output);
  assert.equal(calls[3].name, 'readChar');
  assert.equal(output.con, 1);
  c.bleEventHandler(0, 102, canonical);
  tick(105, output);
  assert.equal(output.glucose, 7);
  assert.equal(output.trend, 0);
  assert.equal(output.age, 31);
  c.bleEventHandler(1, 106, packet({ flags: 0 }));
  tick(106, output);
  assert.equal(output.status, 0, 'ignore unrelated characteristic');
  tick(124, output, 'onExercisePause');
  assert.equal(output.status, 7, 'pause must not freeze freshness');
  c.bleEventHandler(0, 101);
  tick(125, output);
  assert.equal(output.con, 0);
  assert.equal(output.glucose, -1);
  c.bleEventHandler(0, 100);
  tick(126, output);
  assert.equal(calls.at(-1).name, 'enaCharNotf', 'reconnect does not register twice');
  assert.deepEqual(Array.from(c.getSummaryOutputs()), []);
});

test('adapter can pair while waiting for startup time, but a stopped native clock latches', () => {
  const { context: c, calls, tick } = load();
  const output = {};
  c.onLoad({}, output);
  tick(undefined, output);
  assert.equal(output.glucose, -1);
  assert.equal(output.status, 10);
  assert.equal(calls[0].name, 'connect');
  assert.equal(c.receiver.clockFailed, false);
  tick(100, output);
  tick(100, output);
  tick(100, output);
  tick(100, output);
  assert.equal(output.status, 13);
  tick(110, output);
  assert.equal(output.status, 13);
  assert.equal(output.glucose, -1);
  assert.equal(output.trend, 32768);
  assert.equal(output.age, -1);
});

test('native epoch seconds advance even when a float-sized manifest input is unchanged', () => {
  const { context: c, tick, clock } = load();
  const output = {};
  const epoch = 1788632316;
  assert.equal(Math.fround(epoch), Math.fround(epoch + 3));
  c.onLoad({}, output);
  c.receiver.setConnected(true);
  c.configured = true;
  for (let second = 0; second <= 180; second++) {
    tick(epoch + second, output);
    if (second > 0) {
      assert.equal(output.status, 0);
      assert.equal(output.glucose, 7);
      assert.equal(output.trend, 0);
    }
    if (second % 5 === 0) {
      c.bleEventHandler(0, 106, packet({
        age: second % 60, sample: 7 + Math.floor(second / 60), sequence: 9 + second
      }));
    }
  }
  assert.equal(clock.reads, 181);
  assert.equal(c.receiver.now, epoch + 180);
});

test('adapter reports invalid and backwards native time without retaining numeric values', () => {
  for (const [time, status] of [
    [undefined, 11], [null, 11], [NaN, 11], [Infinity, 11], ['101', 11],
    [0, 11], [-1, 11], [99, 12]
  ]) {
    const { context: c, tick } = load();
    const output = {};
    c.onLoad({}, output);
    c.receiver.setConnected(true);
    c.configured = true;
    tick(100, output);
    c.bleEventHandler(0, 106, canonical);
    tick(101, output);
    assert.equal(output.glucose, 7);
    tick(time, output);
    assert.equal(output.status, status);
    assert.equal(output.glucose, -1);
    assert.equal(output.trend, 32768);
    assert.equal(output.age, -1);
    tick(102, output);
    assert.equal(output.status, status, 'clock faults require app reselection');
  }
});

test('async clock reads retain one request, hide values while waiting, and expire normally', () => {
  const { context: c, tick, clock } = load();
  const output = {};
  clock.deferred = true;
  c.onLoad({}, output);
  tick(100, output);
  assert.equal(output.status, 10);
  assert.equal(clock.pending.length, 1);
  c.receiver.setConnected(true);
  c.configured = true;
  c.bleEventHandler(0, 106, canonical);
  assert.equal(c.receiver.packet, null, 'no packet is accepted before a clock is known');
  clock.pending.shift()(100);
  c.bleEventHandler(0, 106, canonical);
  tick(101, output);
  assert.equal(output.glucose, 7);
  assert.equal(clock.pending.length, 1);
  tick(102, output);
  assert.equal(output.status, 10);
  assert.equal(output.glucose, -1, 'an unfinished read cannot reuse the last clock indefinitely');
  assert.equal(clock.pending.length, 1, 'do not accumulate requests');
  clock.pending.shift()(120);
  tick(120, output);
  assert.equal(output.status, 7);
  assert.equal(output.glucose, -1, '20-second heartbeat expires after delayed completion');
  assert.equal(output.age, 50);
});

test('unanswered native reads time out and late callbacks cannot recover them', () => {
  const { context: c, tick, clock } = load();
  const output = {};
  clock.deferred = true;
  c.onLoad({}, output);
  for (let second = 100; second <= 103; second++) tick(second, output);
  assert.equal(clock.pending.length, 1);
  assert.equal(output.status, 14);
  assert.equal(output.glucose, -1);
  clock.pending.shift()(104);
  tick(105, output);
  assert.equal(output.status, 14);
  assert.equal(clock.reads, 1);
});

test('pause/resume refresh native time without treating lifecycle callbacks as stalled ticks', () => {
  const { context: c, tick, clock } = load();
  const output = {};
  c.onLoad({}, output);
  c.receiver.setConnected(true);
  c.configured = true;
  tick(100, output);
  c.bleEventHandler(0, 106, packet({ age: 590 }));
  for (let i = 0; i < 5; i++) {
    tick(100, output, 'onExercisePause');
    tick(100, output, 'onExerciseContinue');
  }
  assert.equal(output.status, 0);
  tick(110, output, 'onExercisePause');
  assert.equal(output.status, 5);
  assert.equal(output.glucose, -1);
  clock.deferred = true;
  tick(120, output, 'onExerciseContinue');
  assert.equal(output.status, 10, 'resume waits for a clock read, not a cached pre-pause value');
  clock.pending.shift()(120);
  tick(121, output);
  assert.equal(output.status, 7);
  assert.equal(output.glucose, -1);
});

test('registration/notification failures retry at most three times', () => {
  const { context: c, calls, tick } = load();
  const output = {};
  c.onLoad({}, output);
  tick(100, output);
  c.bleEventHandler(0, 100);
  for (let i = 0; i < 3; i++) {
    tick(101 + i, output);
    c.bleEventHandler(0, 108);
  }
  tick(105, output);
  assert.equal(calls.filter(call => call.name === 'regUuid').length, 3);
  assert.equal(output.glucose, -1);
});

test('unit setting, screen-only manifest and persistent DEMO labels', () => {
  for (const unit of ['0', '1', null, 'unexpected']) {
    const { context: c } = load(unit);
    c.onLoad({}, {});
    assert.equal(c.getUserInterface().template, unit === '1' ? 'mgdl' : 'mmol');
  }
  const manifest = JSON.parse(files['manifest.json']);
  assert.equal(manifest.type, 'device');
  assert.ok(manifest.out.every(output => output.log === false));
  assert.deepEqual(manifest.in, []);
  assert.match(source, /\$\.get\('\/Dev\/Time',/);
  assert.ok(!source.includes('input.utc'));
  assert.ok(!source.includes('writeChar('));
  assert.ok(!source.includes('localStorage.set'));
  for (const file of ['mmol.html', 'mgdl.html']) {
    const html = files[file];
    assert.ok(html.includes(`>GlucoStride DEMO v${manifest.version}</div>`));
    assert.ok(html.includes('\\uF390\\uF390\\uF390'));
    assert.match(html, /class="f-ico-m p-hc"/);
    assert.match(html, /DISCONNECTED/);
    assert.match(html, /STALE/);
    assert.match(html, /CLOCK WAIT/);
    assert.match(html, /CLOCK INVALID/);
    assert.match(html, /CLOCK BACK/);
    assert.match(html, /CLOCK STOPPED/);
    assert.match(html, /CLOCK NO REPLY/);
  }
});
