'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const vm = require('node:vm');
const { defaultProfile, profiles } = require('./profiles.cjs');

module.exports = function verifyBuild(filename, tools, profileName = defaultProfile) {
  const profile = profiles[profileName];
  const packet = hex => {
    const bytes = Buffer.from(hex, 'hex');
    bytes[0] = bytes[1] = profile.protocol;
    return bytes;
  };
  const Zip = require(require.resolve('adm-zip', { paths: [tools] }));
  const zip = new Zip(filename);
  const names = zip.getEntries().map(entry => entry.entryName).sort();
  assert.deepEqual(names, ['data.jsn', 'main.js', 'manifest.jsn', 'mgdl.xml', 'mmol.xml']);
  const main = zip.readAsText('main.js');
  const manifest = JSON.parse(zip.readAsText('manifest.jsn'));
  assert.equal(manifest.version, profile.version);
  assert.equal(manifest.name, profile.name);
  assert.ok(manifest.out.every(output => output.log === false), 'No exercise health logging');
  assert.deepEqual(manifest.out.map(output => output.name), ['con', 'glucose', 'trend', 'age', 'status']);
  assert.deepEqual(manifest.settings, [{
    shownName: 'Glucose unit', path: 'glucoseUnit', type: 'enum', values: ['mmol/L', 'mg/dL']
  }]);
  const settings = JSON.parse(zip.readAsText('data.jsn'));
  assert.ok(['0', '1'].includes(settings.glucoseUnit), 'Valid initial glucose unit');
  assert.equal(manifest.in.length, 0, 'UTC must not pass through a numeric manifest input');
  const screenFormats = new Map();
  for (const screen of ['mmol.xml', 'mgdl.xml']) {
    const xml = zip.readAsText(screen);
    assert.doesNotMatch(xml,
      /<onTap>|<onActivate>|<touchEnabled>|<triggerBacklight>|tap-target|Output\/taps|READY|<userInput|<pushButton|<idleTime>|setText|setDisplayBrightness/);
    const unit = screen === 'mmol.xml' ? 'mmol/L' : 'mg/dL';
    assert.ok(xml.includes(`>${unit}<`), 'Plain glucose unit label');
    assert.ok(xml.includes(profile.name));
    if (profileName === 'live') {
      assert.ok(xml.includes(`>${profile.name}<`), 'Plain production app title');
      assert.doesNotMatch(xml, /LIVE|EXPERIMENTAL|DEMO|\bv\d+\.\d+/i);
    } else {
      assert.ok(xml.includes(`${profile.name} v${manifest.version}`),
        'Historical demo remains clearly distinguishable');
    }
    const formats = new Map(Array.from(xml.matchAll(
      /<eval><input>Zapp\/\{zapp_index\}\/Output\/(\w+)<\/input><outputFormat>script ([\s\S]*?)<\/outputFormat>/g
    ), ([, name, expression]) => [
      name, vm.runInNewContext(`(${expression.replace(/&lt;/g, '<').replace(/&gt;/g, '>')})`)
    ]));
    screenFormats.set(screen === 'mmol.xml' ? '0' : '1', formats);
    assert.equal(formats.get('glucose')(-1), '--');
    assert.equal(formats.get('trend')(32768), `-- ${unit}/min`);
    for (const [value, text] of [[0.5, '+0.50'], [-0.5, '-0.50'], [0, '0.00'],
      [-0.0005, '0.00'], [0.0005, '0.00']]) {
      assert.equal(formats.get('trend')(value), `${text} ${unit}/min`);
    }
    assert.equal(formats.get('age')(-1), 'Age unknown');
    assert.equal(formats.get('glucose')(screen === 'mmol.xml' ? 7 : 126),
      screen === 'mmol.xml' ? '7.0' : '126');
    for (const [code, label] of [
      [9, 'CLOCK ERROR'], [10, 'CLOCK WAIT'], [11, 'CLOCK INVALID'],
      [12, 'CLOCK BACK'], [13, 'CLOCK STOPPED'], [14, 'CLOCK NO REPLY']
    ]) assert.equal(formats.get('status')(code), label);
  }
  // Exercise the actual minified callback dispatcher, not just the uncompiled source.
  const calls = [];
  let handler;
  let utc = 1788632316;
  let deferClock = false;
  let pendingClock;
  const context = vm.createContext({
    enabledZappId: 42,
    localStorage: { getItem: () => '0' },
    $: {
      get: (resource, callback) => {
        assert.equal(resource, '/Dev/Time');
        if (deferClock) {
          assert.equal(pendingClock, undefined, 'only one clock request may be outstanding');
          pendingClock = callback;
        } else callback(utc);
      }
    },
    appConn: {
      connect: (id, callback, partial, complete) => {
        const uuid = Array.from(Buffer.from(profile.service, 'hex').reverse());
        assert.deepEqual(Array.from(partial), [6, ...uuid]);
        assert.deepEqual(Array.from(complete), [7, ...uuid]);
        handler = callback;
        calls.push('connect');
        return 11;
      },
      regUuid: (id, cid, service, characteristic) => {
        assert.equal(Buffer.from(service).reverse().toString('hex'), profile.service);
        assert.equal(Buffer.from(characteristic).reverse().toString('hex'), profile.characteristic);
        calls.push('register');
      },
      enaCharNotf: () => calls.push('subscribe'),
      readChar: () => calls.push('read')
    }
  });
  const dispatch = vm.runInContext(`(function () { ${main}\n})()`, context);
  // No time input: exercise the compiled outputs with float-sized resources.
  const resources = new Float32Array([0, -1, 32768, -1, 6]);
  const tick = (second, event = 1) => {
    utc = 1788632316 + second;
    dispatch(event, resources);
  };
  dispatch(2, resources); // onLoad
  tick(0);
  handler(0, 100);
  tick(1);
  handler(0, 107);
  tick(2);
  handler(0, 109);
  tick(3);
  handler(0, 102, packet('010100007e0032001e0000000700000009000000'));
  tick(4);
  assert.equal(resources[1], 7);
  assert.equal(resources[2], Math.fround(0.03));
  tick(23, 256); // onExercisePause
  assert.equal(resources[1], -1);
  assert.equal(resources[4], 7);
  tick(24, 512); // onExerciseContinue
  assert.equal(resources[4], 7);
  assert.deepEqual(calls, ['connect', 'register', 'subscribe', 'read']);
  handler(0, 101);
  handler(0, 100);
  tick(25);
  handler(0, 109);
  tick(26);
  const restartedPacket = packet('010100007e003200000000000800000001000000');
  handler(0, 102, restartedPacket);
  tick(27);
  assert.equal(resources[1], 7, 'restarted phone has a lower sequence but a changed sample');
  assert.equal(resources[3], 1);
  tick(45);
  handler(0, 106, restartedPacket);
  tick(46);
  assert.equal(resources[4], 7, 'replayed restart packet cannot renew heartbeat');
  assert.equal(dispatch(4096, resources).template, 'mmol');
  assert.equal(dispatch(8192, resources).length, 0);
  for (let i = 0; i < 3; i++) tick(46);
  assert.equal(resources[4], 13, 'a genuinely stopped native clock still fails closed');
  assert.equal(resources[1], -1);
  assert.equal(resources[2], 32768);
  assert.equal(resources[3], -1);

  deferClock = true;
  dispatch(2, resources);
  tick(100);
  assert.equal(resources[4], 10, 'wait for asynchronous native clock');
  const reply = pendingClock;
  pendingClock = undefined;
  reply(utc);
  tick(101);
  assert.equal(resources[4], 7, 'a valid clock alone is not a connected glucose source');
  for (let second = 102; second <= 104; second++) tick(second);
  assert.equal(resources[4], 14, 'an unanswered clock request cannot keep values visible');

  deferClock = false;
  dispatch(2, resources);
  tick(200);
  handler(0, 100);
  tick(201);
  handler(0, 107);
  tick(202);
  handler(0, 109);
  tick(203);
  const otherProfile = packet('010100007e0032001e0000000700000009000000');
  otherProfile[0] = otherProfile[1] = profile.protocol === 1 ? 2 : 1;
  handler(0, 102, otherProfile);
  tick(204);
  assert.equal(resources[4], 8, 'compiled DEMO and LIVE must reject each other');
  assert.equal(resources[1], -1);
  assert.equal(resources[2], 32768);
  handler(0, 106, packet('010100007e0032001e0000000700000009000000'));
  tick(205);
  assert.equal(resources[1], 7, 'only the selected profile can restore values');

  if (profileName === 'live') require('./verify-live.cjs')(main, screenFormats);
  console.log(`Built ${profile.name} package: native epoch clock, async timeout, expiry, restart/replay, screens passed.`);
};
