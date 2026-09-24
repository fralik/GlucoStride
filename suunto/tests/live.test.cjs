'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { defaultProfile, profiles, readProfile } = require('../scripts/profiles.cjs');

const root = path.resolve(__dirname, '..');
const files = readProfile(root, 'live');
const canonical = Buffer.from('020200007e0032001e0000000700000009000000', 'hex');

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

function unavailable(fields = {}) {
  return packet({ status: 1, glucose: 65535, trend: -32768, age: 0xffffffff, sample: 0, ...fields });
}

function load(unit = '0', profile = 'live') {
  const calls = [];
  const clock = { now: undefined, deferred: false, pending: [] };
  const context = vm.createContext({
    localStorage: { getItem: key => {
      assert.equal(key, 'glucoseUnit');
      return unit;
    } },
    enabledZappId: 42,
    $: {
      get: (resource, callback) => {
        assert.equal(resource, '/Dev/Time');
        if (clock.deferred) clock.pending.push(callback);
        else callback(clock.now);
      }
    },
    appConn: Object.fromEntries(['connect', 'regUuid', 'enaCharNotf', 'readChar'].map(name => [
      name, (...args) => { calls.push({ name, args }); return 11; }
    ]))
  });
  vm.runInContext(readProfile(root, profile)['main.js'], context);
  const tick = (now, output, event = 'evaluate') => {
    clock.now = now;
    context[event]({ utc: Math.fround(now) }, output);
  };
  return { context, calls, clock, tick };
}

function receiver() {
  const r = load().context.createReceiver();
  r.setConnected(true);
  return r;
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test('editor source and default build are LIVE; explicit demo retains its protocol, labels, IDs and units', () => {
  assert.equal(defaultProfile, 'live');
  assert.deepEqual(readProfile(root), files);
  const demo = readProfile(root, 'demo');
  for (const [name, content] of Object.entries(files)) {
    assert.equal(content, fs.readFileSync(path.join(root, name), 'utf8'));
  }
  assert.notEqual(profiles.demo.appId, profiles.live.appId);
  assert.notEqual(profiles.demo.service, profiles.live.service);
  const manifest = JSON.parse(files['manifest.json']);
  assert.equal(manifest.name, 'GlucoStride');
  assert.match(manifest.description, /Unencrypted, unauthenticated/);
  assert.doesNotMatch(manifest.description, /experimental/i);
  assert.ok(manifest.out.every(output => output.log === false));
  assert.deepEqual(manifest.in, []);
  assert.deepEqual(manifest.settings, JSON.parse(demo['manifest.json']).settings);
  assert.equal(files['data.json'], demo['data.json']);
  for (const screen of ['mmol.html', 'mgdl.html']) {
    assert.match(files[screen], />GlucoStride<\/div>/);
    assert.doesNotMatch(files[screen], /LIVE|EXPERIMENTAL|DEMO|\bv\d+\.\d+/i);
    assert.match(demo[screen], /GlucoStride DEMO/);
  }
  assert.throws(() => readProfile(root, 'typo'), /Unknown profile/);
});

test('version stays in metadata while production screens show only the app name', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  assert.equal(profiles.live.version, '1');
  assert.equal(pkg.version, '1.0.0');
  for (const profile of ['live', 'demo']) {
    const source = readProfile(root, profile);
    const manifest = JSON.parse(source['manifest.json']);
    assert.equal(manifest.version, profiles[profile].version);
    for (const screen of ['mmol.html', 'mgdl.html']) {
      if (profile === 'live') {
        assert.ok(source[screen].includes(`>${manifest.name}</div>`));
        assert.doesNotMatch(source[screen], /LIVE|EXPERIMENTAL|\bv\d+\.\d+/i);
      } else {
        assert.ok(source[screen].includes(`>${manifest.name} v${manifest.version}</div>`));
      }
    }
  }
});

test('clean screens have five outputs and no touch diagnostics or workout-control overrides', () => {
  for (const profile of ['live', 'demo']) {
    const source = readProfile(root, profile);
    const names = ['con', 'glucose', 'trend', 'age', 'status'];
    assert.deepEqual(JSON.parse(source['manifest.json']).out.map(output => output.name), names);
    const app = load('0', profile).context;
    const output = {};
    app.onLoad({}, output);
    assert.deepEqual(Object.keys(output).sort(), names.sort());
    assert.equal(typeof app.onEvent, 'undefined');
    for (const screen of ['mmol.html', 'mgdl.html']) {
      assert.match(source[screen], /^<uiView>/);
      assert.doesNotMatch(source[screen],
        /onTap|onActivate|touchEnabled|triggerBacklight|tap-target|Output\/taps|READY|<userInput|<pushButton|idleTime=|setText|setDisplayBrightness/);
      assert.ok(source[screen].includes(`>${screen === 'mmol.html' ? 'mmol/L' : 'mg/dL'}</div>`));
    }
  }
});

test('persistent glucose setting selects both display units, with mmol/L as the default', () => {
  const manifest = JSON.parse(files['manifest.json']);
  assert.deepEqual(manifest.settings, [{
    shownName: 'Glucose unit', path: 'glucoseUnit', type: 'enum', values: ['mmol/L', 'mg/dL']
  }]);
  assert.deepEqual(JSON.parse(files['data.json']), { glucoseUnit: '0' });
  for (const preference of ['0', '1', null, '', 'unexpected']) {
    const { context: c } = load(preference);
    c.onLoad({}, {});
    assert.equal(c.getUserInterface().template, preference === '1' ? 'mgdl' : 'mmol');
    c.onLoad({}, {});
    assert.equal(c.getUserInterface().template, preference === '1' ? 'mgdl' : 'mmol');
    assert.equal(c.localStorage.getItem('glucoseUnit'), preference, 'never overwrite a stored setting');
  }
  const { context: c } = load();
  delete c.localStorage;
  c.onLoad({}, {});
  assert.equal(c.getUserInterface().template, 'mmol');
  c.localStorage = { getItem: () => '1' };
  assert.equal(c.getUserInterface().template, 'mmol', 'unit remains fixed during a workout');
  c.onLoad({}, {});
  assert.equal(c.getUserInterface().template, 'mgdl', 'new workout reads the synced preference');
});

test('rising, falling, flat and unknown rates use the selected glucose unit and signed precision', () => {
  for (const unit of ['0', '1']) {
    const html = files[unit === '0' ? 'mmol.html' : 'mgdl.html'];
    const expression = html.match(/Output\/trend" outputFormat="script ([^"]+)"/)[1];
    const format = vm.runInNewContext(`(${expression.replace(/&lt;/g, '<').replace(/&gt;/g, '>')})`);
    const label = unit === '0' ? 'mmol/L/min' : 'mg/dL/min';
    assert.ok(html.includes(`default="-- ${label}"`));
    for (const [trend, mmol, mgdl] of [
      [50, '+0.03', '+0.50'], [-50, '-0.03', '-0.50'], [0, '0.00', '0.00'],
      [1, '0.00', '+0.01'], [-1, '0.00', '-0.01'],
      [9, '+0.01', '+0.09'], [-9, '-0.01', '-0.09'],
      [32767, '+18.20', '+327.67'], [-32767, '-18.20', '-327.67'],
      [-32768, '--', '--']
    ]) {
      const { context: c, tick } = load(unit);
      const output = {};
      c.onLoad({}, output);
      c.receiver.setConnected(true);
      c.configured = true;
      c.connectionState = 4;
      tick(100, output);
      c.bleEventHandler(0, 106, packet({ trend }));
      tick(101, output);
      const canonicalTrend = trend === -32768 ? null : trend / 100;
      assert.equal(c.receiver.snapshot(101).trend, canonicalTrend, 'wire/history remain mg/dL/min');
      assert.equal(output.trend === 0 ? 0 : output.trend,
        canonicalTrend === null ? 32768 : Number(unit === '0' ? mmol : mgdl));
      assert.equal(format(output.trend), `${unit === '0' ? mmol : mgdl} ${label}`);
      assert.equal(format(Math.fround(output.trend)), `${unit === '0' ? mmol : mgdl} ${label}`);
      assert.equal(output.glucose, unit === '0' ? 7 : 126);
    }
  }
});

test('both display units retain unknown-rate sentinel for every non-OK, invalid and expired state', () => {
  for (const unit of ['0', '1']) {
    const { context: c, tick } = load(unit);
    const output = {};
    c.onLoad({}, output);
    assert.equal(output.trend, 32768);
    c.receiver.setConnected(true);
    c.configured = true;
    c.connectionState = 4;
    tick(100, output);
    for (const status of [1, 2, 3, 4, 5]) {
      c.bleEventHandler(0, 106, unavailable({ status, sequence: status }));
      tick(100 + status, output);
      assert.equal(output.status, status);
      assert.equal(output.glucose, -1);
      assert.equal(output.trend, 32768);
    }
    c.bleEventHandler(0, 106, packet({ reserved: 1, sequence: 6 }));
    tick(106, output);
    assert.equal(output.status, 8);
    assert.equal(output.trend, 32768);
    c.bleEventHandler(0, 106, packet({ age: 599, sequence: 7 }));
    tick(107, output);
    assert.equal(output.status, 5);
    assert.equal(output.trend, 32768);
    c.bleEventHandler(0, 106, packet({ sample: 8, age: 0, sequence: 8 }));
    tick(108, output);
    assert.equal(output.status, 0);
    tick(127, output);
    assert.equal(output.status, 7);
    assert.equal(output.trend, 32768);
    c.bleEventHandler(0, 101);
    tick(128, output);
    assert.equal(output.trend, 32768);
  }
});

test('default CLI entry and unprofiled editor source select LIVE', () => {
  const scripts = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).scripts;
  assert.equal(scripts.build, 'node scripts\\build.cjs');
  assert.equal(scripts['build:live'], 'node scripts\\build.cjs --profile live');
  assert.equal(scripts['build:demo'], 'node scripts\\build.cjs --profile demo');
  const context = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(root, 'main.js'), 'utf8'), context);
  assert.equal(context.liveProfile, true);
  assert.notEqual(context.decodePacket(canonical), null);
  assert.equal(context.decodePacket(packet({ version: 1, flags: 1 })), null);
  assert.equal(Buffer.from(context.serviceUuid).reverse().toString('hex'), profiles.live.service);
  assert.equal(Buffer.from(context.characteristicUuid).reverse().toString('hex'), profiles.live.characteristic);
});

test('v2 canonical vector and LIVE-only flag; demo and live mutually reject packets', () => {
  const c = load().context;
  const d = load('0', 'demo').context;
  assert.deepEqual(plain(c.decodePacket(canonical)), {
    status: 0, glucose: 126, trend: 0.5, age: 30, sample: 7, sequence: 9
  });
  const demo = packet({ version: 1, flags: 1 });
  assert.equal(c.decodePacket(demo), null);
  assert.equal(d.decodePacket(canonical), null);
  assert.notEqual(d.decodePacket(demo), null);
  for (const flags of [0, 1, 3, 4, 255]) assert.equal(c.decodePacket(packet({ flags })), null);
  for (const version of [0, 1, 3, 255]) assert.equal(c.decodePacket(packet({ version })), null);
});

test('live rejects malformed bytes, missing OK fields, reserved and unknown status', () => {
  const c = load().context;
  for (const bytes of [
    null, undefined, '', canonical.toString('hex'), [], canonical.subarray(0, 19),
    Buffer.concat([canonical, Buffer.from([0])]), packet({ status: 6 }), packet({ reserved: 1 }),
    packet({ glucose: 0 }), packet({ glucose: 65535 }), packet({ age: 0xffffffff }),
    packet({ age: 600 }), packet({ age: 0xfffffffe }), packet({ sample: 0 }),
    [2.1, ...canonical.subarray(1)], [NaN, ...canonical.subarray(1)],
    [256, ...canonical.subarray(1)], [-1, ...canonical.subarray(1)],
    ['2', ...canonical.subarray(1)], [Infinity, ...canonical.subarray(1)]
  ]) assert.equal(c.decodePacket(bytes), null);
  assert.equal(c.decodePacket(packet({ glucose: 1 })).glucose, 1);
  assert.equal(c.decodePacket(packet({ glucose: 65534 })).glucose, 65534);
  assert.equal(c.decodePacket(packet({ age: 599 })).age, 599);
});

test('all non-OK states require both sentinels; unknown OK trend is allowed', () => {
  const c = load().context;
  for (let status = 1; status <= 5; status++) {
    for (const sample of [0, 7]) {
      const bytes = unavailable({ status, sample, age: status === 5 ? 600 : 0xffffffff });
      assert.equal(c.decodePacket(bytes).status, status);
      assert.equal(c.decodePacket(bytes).glucose, null);
      assert.equal(c.decodePacket(bytes).trend, null);
      assert.equal(c.decodePacket(unavailable({ status, sample, glucose: 126 })), null);
      assert.equal(c.decodePacket(unavailable({ status, sample, trend: 0 })), null);
    }
  }
  for (const [trend, value] of [[-32768, null], [-32767, -327.67], [32767, 327.67], [0, 0]]) {
    assert.equal(c.decodePacket(packet({ trend })).trend, value);
  }
  const r = receiver();
  assert.equal(r.receive(packet({ trend: -32768 }), 100), true);
  assert.equal(r.snapshot(100).glucose, 126);
  assert.equal(r.snapshot(100).trend, null);
});

test('exact source age and heartbeat boundaries hide both glucose and trend', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  assert.equal(r.snapshot(109.999).status, 0);
  assert.deepEqual(plain(r.snapshot(110)), { status: 5, glucose: null, trend: null, age: 600 });
  const h = receiver();
  h.receive(canonical, 100);
  assert.equal(h.snapshot(119.999).status, 0);
  assert.deepEqual(plain(h.snapshot(120)), { status: 7, glucose: null, trend: null, age: 50 });
});

test('5-second heartbeats cannot rejuvenate a sample; a genuinely new sample can recover', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  for (let time = 105; time <= 130; time += 5) {
    assert.equal(r.receive(packet({ age: 0, sequence: time }), time), true);
    assert.equal(r.snapshot(time).age, 590 + time - 100);
    assert.equal(r.snapshot(time).status, time < 110 ? 0 : 5);
  }
  assert.equal(r.receive(packet({ sample: 8, age: 0, sequence: 131 }), 131), true);
  assert.equal(r.snapshot(131).status, 0);
  assert.equal(r.snapshot(131).age, 0);
});

test('same sample retains maximum age with fractional elapsed time and greater reported age', () => {
  const r = receiver();
  r.receive(canonical, 0);
  r.receive(packet({ age: 50, sequence: 10 }), 0.25);
  r.receive(packet({ age: 0, sequence: 11 }), 0.75);
  assert.equal(r.snapshot(1).age, 50.75);
  assert.equal(r.history.age, 50.75);
});

test('sample-zero outages and same-sample non-OK heartbeats preserve good values and age', () => {
  for (const sample of [0, 7]) {
    const r = receiver();
    r.receive(packet({ age: 590 }), 100);
    assert.equal(r.receive(unavailable({ sample, sequence: 10 }), 105), true);
    assert.equal(r.snapshot(105).status, 1);
    assert.equal(r.snapshot(105).glucose, null);
    assert.equal(r.receive(packet({ age: 0, sequence: 11, glucose: 127 }), 106), false);
    assert.equal(r.snapshot(106).status, 8);
    assert.equal(r.receive(packet({ age: 0, sequence: 11, trend: 100 }), 107), false);
    assert.equal(r.receive(packet({ age: 0, sequence: 11 }), 110), true);
    assert.equal(r.snapshot(110).age, 600);
    assert.equal(r.snapshot(110).status, 5);
  }
});

test('all source statuses suppress after OK and never overwrite known-good identity', () => {
  for (let status = 1; status <= 5; status++) {
    const r = receiver();
    r.receive(canonical, 100);
    assert.equal(r.receive(unavailable({ status, sample: 7, sequence: 10 }), 101), true);
    assert.equal(r.snapshot(101).status, status);
    assert.equal(r.snapshot(101).glucose, null);
    assert.equal(r.snapshot(101).trend, null);
    assert.equal(r.receive(packet({ glucose: 127, sequence: 11 }), 102), false);
    assert.equal(r.receive(packet({ sequence: 11 }), 103), true);
    assert.equal(r.snapshot(103).age, 33);
  }
});

test('known and unknown trend cannot mutate for the same known-good sample', () => {
  for (const [first, changed] of [[50, -32768], [-32768, 50]]) {
    const r = receiver();
    r.receive(packet({ trend: first }), 100);
    r.receive(unavailable({ sequence: 10 }), 101);
    r.setConnected(false);
    r.setConnected(true);
    assert.equal(r.receive(packet({ trend: changed, sequence: 0 }), 102), false);
    assert.equal(r.receive(packet({ trend: first, sequence: 0 }), 103), true);
    assert.equal(r.snapshot(103).trend, first === -32768 ? null : first / 100);
  }
});

test('real reconnect rebases sequence once even for same sample and retains source history', () => {
  for (const sequence of [0, 1, 9, 0xffffffff, 0x80000009]) {
    const r = receiver();
    r.receive(packet({ age: 590 }), 100);
    r.setConnected(false);
    assert.equal(r.snapshot(105).status, 7);
    r.setConnected(true);
    assert.equal(r.receive(packet({ age: 0, sequence }), 110), true);
    assert.equal(r.snapshot(110).age, 600);
    assert.equal(r.snapshot(110).status, 5);
    assert.equal(r.receive(packet({ age: 0, sequence }), 125), false);
    assert.equal(r.receive(packet({ age: 0, sequence: (sequence - 1) >>> 0 }), 126), false);
    assert.equal(r.snapshot(130).status, 7);
    assert.equal(r.receive(packet({ sample: 8, age: 0, sequence: (sequence + 1) >>> 0 }), 131), true);
    assert.equal(r.snapshot(131).status, 0);
  }
});

test('sample-zero outage on restart consumes rebase but cannot erase retained sample history', () => {
  const r = receiver();
  r.receive(packet({ age: 590 }), 100);
  r.setConnected(false);
  r.setConnected(true);
  assert.equal(r.receive(unavailable({ sequence: 0 }), 105), true);
  assert.equal(r.receive(packet({ age: 0, sequence: 0 }), 106), false);
  assert.equal(r.receive(packet({ age: 0, sequence: 1, glucose: 127 }), 107), false);
  assert.equal(r.receive(packet({ age: 0, sequence: 1 }), 110), true);
  assert.equal(r.snapshot(110).age, 600);
  assert.equal(r.snapshot(110).glucose, null);
});

test('outage then reconnect then phone restart cannot make a repeated record young or different', () => {
  const r = receiver();
  r.receive(packet({ age: 580 }), 100);
  r.receive(unavailable({ sequence: 10 }), 105);
  r.setConnected(false);
  r.snapshot(115);
  r.setConnected(true);
  assert.equal(r.receive(packet({ sequence: 0, age: 0, glucose: 127 }), 120), false);
  assert.equal(r.receive(packet({ sequence: 0, age: 0 }), 121), true);
  assert.equal(r.snapshot(121).age, 601);
  assert.equal(r.snapshot(121).status, 5);
});

test('duplicate connected callbacks, new sample or heartbeat timeout cannot rebase sequence', () => {
  const r = receiver();
  r.receive(canonical, 100);
  r.setConnected(true);
  assert.equal(r.lastRx, 100);
  assert.equal(r.receive(packet({ sample: 8, sequence: 1 }), 101), false);
  assert.equal(r.snapshot(119.999).status, 0);
  assert.equal(r.snapshot(120).status, 7);
  assert.equal(r.receive(packet({ sample: 8, sequence: 1 }), 125), false);
  assert.equal(r.receive(packet({ sample: 8, sequence: 10 }), 126), true);
});

test('duplicates, out-of-order and half-range jumps cannot renew heartbeat; sequence wraps', () => {
  const r = receiver();
  r.receive(packet({ sequence: 0xffffffff }), 100);
  assert.equal(r.receive(packet({ sequence: 0 }), 105), true);
  for (const sequence of [0, 0xffffffff, 0x80000000]) {
    assert.equal(r.receive(packet({ sequence }), 110), false);
  }
  assert.equal(r.lastRx, 105);
  assert.equal(r.snapshot(125).status, 7);
  assert.equal(r.receive(packet({ sequence: 1 }), 126), true);
});

test('epoch sample order rejects older records across source faults, reconnect and counter restart', () => {
  const r = receiver();
  r.receive(packet({ sample: 1788632300 }), 100);
  r.receive(packet({ sample: 1788632360, sequence: 10 }), 105);
  assert.equal(r.receive(packet({ sample: 1788632300, sequence: 11 }), 106), false);
  assert.equal(r.snapshot(106).status, 8);
  r.receive(unavailable({ sequence: 11 }), 107);
  r.setConnected(false);
  r.setConnected(true);
  assert.equal(r.receive(packet({ sample: 1788632300, sequence: 0 }), 110), false);
  assert.equal(r.receive(packet({ sample: 1788632360, sequence: 0 }), 111), true);
  assert.equal(r.snapshot(111).age, 36);
  assert.equal(r.receive(packet({ sample: 1788632420, age: 0, sequence: 1 }), 112), true);
  assert.equal(r.snapshot(112).age, 0);
});

test('a newer non-OK record still prevents epoch rollback and preserves known age', () => {
  const r = receiver();
  r.receive(canonical, 100);
  r.receive(unavailable({ sample: 8, age: 590, sequence: 10 }), 105);
  assert.equal(r.receive(packet({ sample: 7, sequence: 11 }), 106), false);
  assert.equal(r.receive(packet({ sample: 8, age: 0, sequence: 11 }), 115), true);
  assert.equal(r.snapshot(115).status, 5);
  assert.equal(r.snapshot(115).age, 600);
});

test('malformed live and demo packets fail closed, cannot consume reconnect rebase or erase history', () => {
  const r = receiver();
  r.receive(canonical, 100);
  r.setConnected(false);
  r.setConnected(true);
  assert.equal(r.receive(packet({ version: 1, flags: 1, sequence: 0 }), 101), false);
  assert.equal(r.snapshot(101).status, 8);
  assert.equal(r.receive(packet({ sequence: 0 }), 102), true);
  assert.equal(r.snapshot(102).age, 32);
  assert.equal(r.receive(packet({ flags: 3, sequence: 1 }), 103), false);
  assert.equal(r.receive(packet({ sequence: 0 }), 104), false);
  assert.equal(r.snapshot(104).status, 8);
  assert.equal(r.receive(packet({ sequence: 1 }), 105), true);
});

test('sender owns absolute timestamp validity; watch never compares source epoch with its UTC', () => {
  for (const [sample, now] of [[0xffffffff, 100], [7, 1788632316]]) {
    const r = receiver();
    assert.equal(r.receive(packet({ sample }), now), true);
    assert.equal(r.snapshot(now).status, 0);
    assert.equal(r.snapshot(now).age, 30);
  }
});

test('missing, invalid, backwards clock latches across outage, reconnect and valid packets', () => {
  for (const time of [99, NaN, Infinity, undefined, null, '100', -1]) {
    const r = receiver();
    r.receive(canonical, 100);
    assert.equal(r.snapshot(time).status, 9);
    r.setConnected(false);
    r.setConnected(true);
    assert.equal(r.receive(unavailable({ sequence: 0 }), 200), false);
    assert.equal(r.receive(packet({ sequence: 1 }), 201), false);
    assert.deepEqual(plain(r.snapshot(202)), { status: 9, glucose: null, trend: null, age: null });
  }
});

test('live native callbacks use distinct UUIDs, units, initial read, notifications, pause and resume', () => {
  for (const unit of ['0', '1']) {
    const { context: c, calls, tick } = load(unit);
    const output = {};
    c.onLoad({}, output);
    tick(100, output);
    assert.deepEqual(Array.from(calls[0].args[2]), [6, ...c.serviceUuid]);
    assert.deepEqual(Array.from(calls[0].args[3]), [7, ...c.serviceUuid]);
    assert.equal(Buffer.from(c.serviceUuid).reverse().toString('hex'), profiles.live.service);
    assert.equal(Buffer.from(c.characteristicUuid).reverse().toString('hex'), profiles.live.characteristic);
    c.bleEventHandler(0, 100);
    tick(101, output);
    c.bleEventHandler(0, 107);
    tick(102, output);
    c.bleEventHandler(0, 109);
    tick(103, output);
    c.bleEventHandler(0, 102, canonical);
    tick(104, output);
    assert.equal(output.glucose, unit === '0' ? 7 : 126);
    assert.equal(output.trend, unit === '0' ? 0.03 : 0.5);
    assert.equal(output.age, 31);
    assert.equal(c.getUserInterface().template, unit === '0' ? 'mmol' : 'mgdl');
    tick(123, output, 'onExercisePause');
    assert.equal(output.status, 7);
    assert.equal(output.glucose, -1);
    tick(124, output, 'onExerciseContinue');
    c.bleEventHandler(0, 106, packet({ sequence: 10, trend: -32768, sample: 8 }));
    tick(125, output);
    assert.equal(output.glucose, unit === '0' ? 7 : 126);
    assert.equal(output.trend, 32768);
    c.bleEventHandler(0, 106, packet({ version: 1, flags: 1, sequence: 11 }));
    tick(126, output);
    assert.equal(output.status, 8);
    assert.equal(output.glucose, -1);
    assert.deepEqual(calls.map(call => call.name), ['connect', 'regUuid', 'enaCharNotf', 'readChar']);
    assert.deepEqual(Array.from(c.getSummaryOutputs()), []);
  }
});

test('live adapter retains stopped, invalid, backwards and unanswered native-clock guards', () => {
  for (const [bad, expected] of [[undefined, 11], [0, 11], [NaN, 11], [99, 12]]) {
    const { context: c, tick } = load();
    const output = {};
    c.onLoad({}, output);
    c.receiver.setConnected(true);
    c.configured = true;
    tick(100, output);
    c.bleEventHandler(0, 106, canonical);
    tick(101, output);
    assert.equal(output.glucose, 7);
    tick(bad, output);
    assert.equal(output.status, expected);
    assert.equal(output.glucose, -1);
    assert.equal(output.trend, 32768);
    assert.equal(output.age, -1);
  }
  const { context: c, tick, clock } = load();
  const output = {};
  c.onLoad({}, output);
  for (let n = 0; n < 4; n++) tick(100, output);
  assert.equal(output.status, 13);
  c.onLoad({}, output);
  clock.deferred = true;
  tick(100, output);
  assert.equal(output.status, 10);
  for (let n = 101; n <= 103; n++) tick(n, output);
  assert.equal(output.status, 14);
  assert.equal(clock.pending.length, 1);
  clock.pending.shift()(104);
  tick(105, output);
  assert.equal(output.status, 14);
  assert.equal(output.glucose, -1);
});
