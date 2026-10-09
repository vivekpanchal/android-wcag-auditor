'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toCsv, toHtml } = require('./export');

const base = {
  id: 1, packageName: 'com.example', screen: 'Main', timestamp: 0, severity: 'serious',
  wcagSC: '2.5.5', wcagLevel: 'AAA', elementDescription: 'Button', description: 'Too small',
};
const fix = (over = {}) => JSON.stringify({
  summary: 'Make it <48dp>', views: 'android:minHeight="48dp"',
  compose: 'Modifier.minimumInteractiveComponentSize()',
  docUrl: 'https://developer.android.com/guide/topics/ui/accessibility/apps', framework: 'compose', ...over,
});

test('HTML shows the escaped summary, the detected framework snippet and the https doc link', () => {
  const html = toHtml([{ ...base, suggestedFix: fix() }]);
  assert.match(html, /Make it &lt;48dp&gt;/);
  assert.match(html, /Modifier\.minimumInteractiveComponentSize\(\)/);
  assert.doesNotMatch(html, /android:minHeight/);
  assert.match(html, /href="https:\/\/developer\.android\.com\/guide\/topics\/ui\/accessibility\/apps"/);
});

test('HTML drops non-https doc links', () => {
  const html = toHtml([{ ...base, suggestedFix: fix({ docUrl: 'javascript:alert(1)' }) }]);
  assert.doesNotMatch(html, /javascript:/);
});

test('HTML shows a legacy plain-text fix as the summary', () => {
  assert.match(toHtml([{ ...base, suggestedFix: 'Add a label' }]), /How to fix:<\/strong> Add a label/);
});

test('CSV puts the fix summary in the suggestedFix column, not raw JSON', () => {
  const csv = toCsv([{ ...base, suggestedFix: fix() }]);
  assert.match(csv, /Make it <48dp>/);
  assert.doesNotMatch(csv, /minimumInteractiveComponentSize/);
});

test('HTML survives non-string fix fields', () => {
  const html = toHtml([{ ...base, suggestedFix: JSON.stringify({ summary: 'S', compose: { a: 1 }, framework: 'compose', docUrl: 42 }) }]);
  assert.match(html, /How to fix:<\/strong> S/);
  assert.doesNotMatch(html, /\[object Object\]/);
});
