'use strict';

const fs = require('node:fs');
const path = require('node:path');

const defaultProfile = 'live';

const profiles = {
  demo: {
    appId: 'GLUCOSTR', name: 'GlucoStride DEMO', version: '0.2', protocol: 1,
    service: '7b9e10006d8b4f3a9c212e8a6f0d5b47',
    characteristic: '7b9e10016d8b4f3a9c212e8a6f0d5b47'
  },
  live: {
    appId: 'GLUCOLIV', name: 'GlucoStride', version: '1', protocol: 2,
    service: '7b9e20006d8b4f3a9c212e8a6f0d5b47',
    characteristic: '7b9e20016d8b4f3a9c212e8a6f0d5b47'
  }
};

function replaceExactly(text, from, to, count) {
  if (text.split(from).length - 1 !== count) {
    throw new Error(`Profile source changed: expected ${count} occurrences of ${from}`);
  }
  return text.split(from).join(to);
}

function readProfile(root, name = defaultProfile) {
  if (!Object.hasOwn(profiles, name)) throw new Error(`Unknown profile: ${name}`);
  const files = Object.fromEntries(['main.js', 'manifest.json', 'data.json', 'mmol.html', 'mgdl.html']
    .map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')]));
  if (name === 'demo') {
    files['main.js'] = replaceExactly(files['main.js'], 'var liveProfile = true;',
      'var liveProfile = false;', 1);
    files['main.js'] = replaceExactly(files['main.js'], ', 32, 158, 123]',
      ', 16, 158, 123]', 4);
    const manifest = JSON.parse(files['manifest.json']);
    manifest.name = profiles.demo.name;
    manifest.version = profiles.demo.version;
    manifest.description = 'Synthetic glucose only. Not for treatment. No pump connection.';
    files['manifest.json'] = JSON.stringify(manifest, null, 2) + '\n';
    for (const screen of ['mmol.html', 'mgdl.html']) {
      files[screen] = replaceExactly(files[screen].replace(/\r\n/g, '\n'),
        '<div class="sp-t-s p-hc" style="top:9%;">GlucoStride</div>',
        `<div class="sp-t-s p-hc" style="top:9%;">GlucoStride DEMO v${profiles.demo.version}</div>`, 1);
    }
  }
  return files;
}

module.exports = { defaultProfile, profiles, readProfile };
