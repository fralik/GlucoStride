'use strict';

const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const { defaultProfile, profiles, readProfile } = require('./profiles.cjs');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const sourceOption = args.indexOf('--source');
const sourcePackage = sourceOption !== -1;
if (sourcePackage) args.splice(sourceOption, 1);
const profileOption = args.indexOf('--profile');
const profileName = profileOption === -1 ? defaultProfile : args.splice(profileOption, 2)[1];
const extension = path.resolve(args[0] || process.env.SUUNTOPLUS_EXTENSION ||
  path.join(root, '.sdk', 'unpacked', 'extension'));
const tools = path.join(extension, 'node_modules', '@suunto-internal', 'suuntoplus-tools');

async function main() {
  if (args.length > 1 || !profileName) {
    throw new Error('Usage: build.cjs [extension-directory] [--profile demo|live] [--source]');
  }
  if (sourcePackage && profileName !== 'live') {
    throw new Error('Store source packaging is only available for the LIVE app.');
  }
  const files = readProfile(root, profileName);
  const profile = profiles[profileName];
  if (!fs.existsSync(path.join(tools, 'package.json'))) {
    throw new Error('Official SDK missing. See README; pass the extracted extension directory as the first argument.');
  }
  const editorVersion = JSON.parse(fs.readFileSync(path.join(extension, 'package.json'))).version;
  const toolsVersion = JSON.parse(fs.readFileSync(path.join(tools, 'package.json'))).version;
  if (editorVersion !== '1.42.0' || toolsVersion !== '2.1.5') {
    throw new Error(`SDK version not examined: Editor ${editorVersion}, tools ${toolsVersion}. Expected 1.42.0 / 2.1.5.`);
  }
  const Ajv = require(path.join(tools, 'node_modules', 'ajv'));
  const schema = JSON.parse(fs.readFileSync(path.join(extension, 'schema', 'manifest.json')));
  const manifest = JSON.parse(files['manifest.json']);
  const validate = new Ajv({ strict: false }).compile(schema);
  if (!validate(manifest)) throw new Error(JSON.stringify(validate.errors));
  const output = path.join(root, 'dist');
  const work = path.join(root, profileName === 'demo' ? '.build-work' : '.build-work-live');
  const input = profileName === defaultProfile && !sourcePackage ? root : path.join(work, 'source');
  const compiled = path.join(work, 'compiled');
  fs.mkdirSync(output, { recursive: true });
  fs.mkdirSync(work, { recursive: true });
  fs.mkdirSync(compiled, { recursive: true });
  if (input !== root) {
    fs.mkdirSync(input, { recursive: true });
    for (const [file, content] of Object.entries(files)) fs.writeFileSync(path.join(input, file), content);
  }
  // Use the official exported API to keep all build intermediates inside the project.
  const sdk = require(tools);
  sdk.logger.getLogger().level = 'warn';
  const result = await sdk.buildApp(profile.appId, input, output, {
    displayId: 'o',
    languageCode: 'en',
    optimize: true,
    preserveModificationTime: true,
    tmpDirectory: compiled,
    zipDirectory: path.join(work, 'zip')
  });
  if (!result.success || result.fileNames.length === 0) throw new Error('Official SuuntoPlus build failed.');
  require('./verify-build.cjs')(path.join(output, `${profile.appId}-o.dev`), tools, profileName);
  if (sourcePackage) {
    const filename = path.join(output, `${profile.appId}-${profile.version}-source.zip`);
    await sdk.createSourcePackage(input, filename);
    const Zip = require(require.resolve('adm-zip', { paths: [tools] }));
    const zip = new Zip(filename);
    assert.deepEqual(zip.getEntries().map(entry => entry.entryName).sort(), Object.keys(files).sort());
    for (const [file, content] of Object.entries(files)) assert.equal(zip.readAsText(file), content);
    console.log(`Verified LIVE source package: ${filename} (local only; not submitted)`);
  }
  console.log(`Editor ${editorVersion}; tools ${toolsVersion}; display o (Suunto Vertical)`);
  console.log(JSON.stringify(result, null, 2));
  fs.rmSync(work, { recursive: true, force: true });
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
