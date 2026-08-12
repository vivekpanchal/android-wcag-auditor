const { test, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

// Point the store at a throwaway db file for this test run so tests don't
// leak state into the dev db (server/data.db) or between runs.
const dbPath = path.join(os.tmpdir(), `a11y-store-test-${process.pid}-${Date.now()}.db`);
process.env.A11Y_DB_PATH = dbPath;

let store = require('./store');

beforeEach(() => {
  store.clearIssues();
});

after(() => {
  try { store.close(); } catch { /* already closed by the restart test */ }
  try { fs.rmSync(dbPath, { force: true }); } catch { /* ignore */ }
});

test('addReport applies the report-level screenshot to every stored issue', () => {
  const stored = store.addReport({
    packageName: 'com.example.app',
    screen: 'MainScreen',
    timestamp: 1000,
    screenshot: 'base64pngdata',
    issues: [
      { severity: 'critical', wcagSC: '1.1.1', wcagLevel: 'A', elementDescription: 'Button', description: 'Missing label' },
      { severity: 'serious', wcagSC: '1.4.3', wcagLevel: 'AA', elementDescription: 'Text', description: 'Low contrast' },
    ],
  });

  assert.equal(stored.length, 2);
  assert.equal(stored[0].screenshot, 'base64pngdata');
  assert.equal(stored[1].screenshot, 'base64pngdata');
});

test('addReport stores a null screenshot when the report has none', () => {
  const stored = store.addReport({
    packageName: 'com.example.app',
    issues: [{ severity: 'minor', wcagSC: '2.5.5', wcagLevel: 'AAA', elementDescription: 'Icon', description: 'Touch target too small' }],
  });

  assert.equal(stored[0].screenshot, null);
});

test('issues survive a server restart (same db file, fresh module instance)', () => {
  store.addReport({
    packageName: 'com.example.app',
    screen: 'MainScreen',
    timestamp: 2000,
    issues: [{ severity: 'critical', wcagSC: '1.1.1', wcagLevel: 'A', elementDescription: 'Button', description: 'Missing label', bounds: { x: 1, y: 2, width: 3, height: 4 } }],
  });

  // Simulate a process restart: close the db handle, drop the cached module,
  // and re-require it against the same A11Y_DB_PATH. Closing first matters —
  // a real restart drops all handles, and leaving this one open is flaky on
  // Windows (file still locked) besides not representing a real restart.
  store.close();
  delete require.cache[require.resolve('./store')];
  store = require('./store');

  const issues = store.getIssues();
  assert.equal(issues.length, 1);
  assert.equal(issues[0].description, 'Missing label');
  assert.deepEqual(issues[0].bounds, { x: 1, y: 2, width: 3, height: 4 });
});
