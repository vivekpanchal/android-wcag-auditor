const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const store = require('./store');

beforeEach(() => {
  store.clearIssues();
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
