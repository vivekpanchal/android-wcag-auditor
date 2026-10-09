// @vitest-environment node
// (jsdom gives import.meta.url a non-file scheme; this test only reads CSS.)
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

const indexCss = readFileSync(new URL('./index.css', import.meta.url), 'utf8');

function block(selector) {
  const start = indexCss.indexOf(selector);
  if (start < 0) throw new Error(`selector ${selector} not found`);
  return indexCss.slice(start, indexCss.indexOf('}', start));
}
const light = block(':root {');
const darkMedia = block(':root:where(:not([data-theme="light"])) {');
const dark = block(':root[data-theme="dark"] {');

// Tokens not redefined in a dark block inherit the light value.
function token(name, theme) {
  const re = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`);
  const m = theme.match(re) || light.match(re);
  if (!m) throw new Error(`token --${name} not found`);
  return m[1];
}

function luminance(hex) {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('dashboard colour contrast (SC 1.4.3)', () => {
  for (const [name, theme] of [['light', light], ['dark (media)', darkMedia], ['dark', dark]]) {
    it(`link text passes on card surface and page plane in ${name}`, () => {
      for (const bg of ['surface-1', 'page-plane']) {
        expect(contrast(token('link', theme), token(bg, theme))).toBeGreaterThanOrEqual(4.5);
      }
    });
  }
});
