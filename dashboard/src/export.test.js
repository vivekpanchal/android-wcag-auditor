import { it, expect, vi } from 'vitest';
import { toCsv, toHtml } from './export';

const base = {
  id: 1, packageName: 'com.example', screen: 'Main', timestamp: 0, severity: 'serious',
  wcagSC: '2.5.5', wcagLevel: 'AAA', elementDescription: 'Button', description: 'Too small',
};
const fix = (over = {}) => JSON.stringify({
  summary: 'Make it <48dp>', views: 'android:minHeight="48dp"',
  compose: 'Modifier.minimumInteractiveComponentSize()',
  docUrl: 'https://developer.android.com/guide/topics/ui/accessibility/apps', framework: 'compose', ...over,
});

it('HTML shows the escaped summary, the detected framework snippet and the https doc link', async () => {
  const html = await toHtml([{ ...base, suggestedFix: fix() }]);
  expect(html).toMatch(/Make it &lt;48dp&gt;/);
  expect(html).toMatch(/Modifier\.minimumInteractiveComponentSize\(\)/);
  expect(html).not.toMatch(/android:minHeight/);
  expect(html).toMatch(/href="https:\/\/developer\.android\.com\/guide\/topics\/ui\/accessibility\/apps"/);
});

it('HTML drops non-https doc links', async () => {
  const html = await toHtml([{ ...base, suggestedFix: fix({ docUrl: 'javascript:alert(1)' }) }]);
  expect(html).not.toMatch(/javascript:/);
});

it('HTML shows a legacy plain-text fix as the summary', async () => {
  expect(await toHtml([{ ...base, suggestedFix: 'Add a label' }])).toMatch(/How to fix:<\/strong> Add a label/);
});

it('CSV puts the fix summary in the suggestedFix column, not raw JSON', async () => {
  const csv = toCsv([{ ...base, suggestedFix: fix() }]);
  expect(csv).toMatch(/Make it <48dp>/);
  expect(csv).not.toMatch(/minimumInteractiveComponentSize/);
});

it('HTML survives non-string fix fields', async () => {
  const html = await toHtml([{ ...base, suggestedFix: JSON.stringify({ summary: 'S', compose: { a: 1 }, framework: 'compose', docUrl: 42 }) }]);
  expect(html).toMatch(/How to fix:<\/strong> S/);
  expect(html).not.toMatch(/\[object Object\]/);
});

it('embeds each screenshot once as a data URL', async () => {
  const f = vi.fn(async () => 'data:image/webp;base64,AAA');
  const html = await toHtml([{ ...base, screenshotUrl: '/api/v1/screenshots/a.webp' }, { ...base, id: 2, screenshotUrl: '/api/v1/screenshots/a.webp' }], f);
  expect(f).toHaveBeenCalledTimes(1);
  expect(html).toContain('src="data:image/webp;base64,AAA"');
});

it('omits the image when the screenshot cannot be fetched', async () => {
  const html = await toHtml([{ ...base, screenshotUrl: '/x.webp' }], async () => null);
  expect(html).not.toContain('<img');
});
