import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App, { ScreenshotWithHighlight } from './App';
import { severityRank, matchApps, filterIssues, groupIssues } from './logic';

describe('severityRank', () => {
  it('ranks known severities from critical (most severe) to minor', () => {
    expect(severityRank('critical')).toBe(0);
    expect(severityRank('serious')).toBe(1);
    expect(severityRank('moderate')).toBe(2);
    expect(severityRank('minor')).toBe(3);
  });

  it('ranks unknown/missing severities last', () => {
    expect(severityRank('unknown')).toBe(4);
    expect(severityRank(undefined)).toBe(4);
  });
});

describe('filterIssues', () => {
  const issues = [
    { id: 1, severity: 'critical', wcagLevel: 'A', screen: 'Home', elementDescription: 'Login button', description: 'Missing label' },
    { id: 2, severity: 'minor', wcagLevel: 'AA', screen: 'Home', elementDescription: 'Icon', description: 'Low contrast text' },
    { id: 3, severity: 'serious', wcagLevel: 'AA', screen: 'Settings', elementDescription: 'Toggle', description: 'No accessible name' },
  ];
  const noFilters = { severityFilter: 'all', levelFilter: 'all', screenFilter: 'all', search: '' };

  it('returns everything when all filters are "all" and search is empty', () => {
    expect(filterIssues(issues, noFilters)).toEqual(issues);
  });

  it('filters by severity', () => {
    expect(filterIssues(issues, { ...noFilters, severityFilter: 'critical' })).toEqual([issues[0]]);
  });

  it('filters by WCAG level', () => {
    expect(filterIssues(issues, { ...noFilters, levelFilter: 'A' })).toEqual([issues[0]]);
  });

  it('filters by screen', () => {
    expect(filterIssues(issues, { ...noFilters, screenFilter: 'Settings' })).toEqual([issues[2]]);
  });

  it('filters by case-insensitive search across element + description', () => {
    expect(filterIssues(issues, { ...noFilters, search: 'CONTRAST' })).toEqual([issues[1]]);
    expect(filterIssues(issues, { ...noFilters, search: 'login' })).toEqual([issues[0]]);
  });

  it('combines filters (AND semantics)', () => {
    expect(filterIssues(issues, { ...noFilters, screenFilter: 'Home', severityFilter: 'minor' })).toEqual([issues[1]]);
  });
});

describe('groupIssues', () => {
  it('groups by screen and falls back to "Unknown screen" when missing', () => {
    const issues = [
      { id: 1, severity: 'minor', screen: 'Home' },
      { id: 2, severity: 'critical' },
    ];
    const grouped = groupIssues(issues);
    expect([...grouped.keys()]).toEqual(['Home', 'Unknown screen']);
    expect(grouped.get('Home')).toEqual([issues[0]]);
    expect(grouped.get('Unknown screen')).toEqual([issues[1]]);
  });

  it('sorts each group by severity, most severe first', () => {
    const issues = [
      { id: 1, severity: 'minor', screen: 'Home' },
      { id: 2, severity: 'critical', screen: 'Home' },
      { id: 3, severity: 'moderate', screen: 'Home' },
    ];
    const grouped = groupIssues(issues);
    expect(grouped.get('Home').map((i) => i.id)).toEqual([2, 3, 1]);
  });
});

describe('matchApps', () => {
  const apps = [
    { appName: 'Zeta', packageName: 'com.zeta.app' },
    { appName: 'Alpha', packageName: 'com.alpha.app' },
    { appName: 'Beta Notes', packageName: 'com.example.beta' },
  ];

  it('returns all apps sorted alphabetically by name when query is empty', () => {
    expect(matchApps(apps, '').map((a) => a.appName)).toEqual(['Alpha', 'Beta Notes', 'Zeta']);
  });

  it('matches case-insensitively against appName', () => {
    expect(matchApps(apps, 'zeta').map((a) => a.appName)).toEqual(['Zeta']);
  });

  it('matches case-insensitively against packageName', () => {
    expect(matchApps(apps, 'COM.EXAMPLE').map((a) => a.appName)).toEqual(['Beta Notes']);
  });

  it('sorts matched results alphabetically', () => {
    expect(matchApps(apps, 'com.').map((a) => a.appName)).toEqual(['Alpha', 'Beta Notes', 'Zeta']);
  });
});

describe('ScreenshotWithHighlight', () => {
  it('positions the highlight box as a percentage of the image natural size', () => {
    const bounds = { x: 50, y: 25, width: 100, height: 50 };
    render(<ScreenshotWithHighlight screenshot="AAAA" bounds={bounds} alt="a screenshot" wrapClassName="wrap" imgClassName="thumb" />);

    const img = screen.getByAltText('a screenshot');
    Object.defineProperty(img, 'naturalWidth', { value: 200, configurable: true });
    Object.defineProperty(img, 'naturalHeight', { value: 100, configurable: true });
    fireEvent.load(img);

    const highlight = document.querySelector('.issue-highlight');
    expect(highlight).toBeTruthy();
    expect(highlight.style.left).toBe('25%');
    expect(highlight.style.top).toBe('25%');
    expect(highlight.style.width).toBe('50%');
    expect(highlight.style.height).toBe('50%');
  });

  it('renders no highlight before the image has loaded', () => {
    render(<ScreenshotWithHighlight screenshot="AAAA" bounds={{ x: 0, y: 0, width: 10, height: 10 }} alt="a screenshot" />);
    expect(document.querySelector('.issue-highlight')).toBeNull();
  });

  it('renders no highlight when there are no bounds', () => {
    render(<ScreenshotWithHighlight screenshot="AAAA" bounds={null} alt="a screenshot" />);
    const img = screen.getByAltText('a screenshot');
    Object.defineProperty(img, 'naturalWidth', { value: 200, configurable: true });
    Object.defineProperty(img, 'naturalHeight', { value: 100, configurable: true });
    fireEvent.load(img);
    expect(document.querySelector('.issue-highlight')).toBeNull();
  });
});

// --- App-level: WebSocket message handling (dedup-by-id) ---

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.onopen = null;
    this.onclose = null;
    this.onmessage = null;
    FakeWebSocket.instances.push(this);
  }
  send() {}
  close() {}
}
FakeWebSocket.instances = [];

function mockFetchJson(byPath) {
  return vi.fn((url) => {
    const path = Object.keys(byPath).find((p) => url.endsWith(p));
    return Promise.resolve({ json: () => Promise.resolve(byPath[path]) });
  });
}

describe('App WebSocket "issues" message handling', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal(
      'fetch',
      mockFetchJson({
        '/issues': [],
        '/control': { targetPackage: null, auditing: false },
        '/apps': [],
        '/device': { online: false },
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('appends new issues and dedups by id on repeated "issues" messages', async () => {
    render(<App />);

    const ws = FakeWebSocket.instances[0];
    expect(ws).toBeTruthy();

    act(() => {
      ws.onmessage({
        data: JSON.stringify({
          type: 'issues',
          issues: [
            { id: 1, severity: 'critical', elementDescription: 'A', description: 'a', timestamp: Date.now() },
            { id: 2, severity: 'minor', elementDescription: 'B', description: 'b', timestamp: Date.now() },
          ],
        }),
      });
    });

    expect(document.querySelector('.stat-total .stat-value').textContent).toBe('2');

    // id 2 is a duplicate and should be dropped; id 3 is new and should be appended.
    act(() => {
      ws.onmessage({
        data: JSON.stringify({
          type: 'issues',
          issues: [
            { id: 2, severity: 'minor', elementDescription: 'B', description: 'b', timestamp: Date.now() },
            { id: 3, severity: 'moderate', elementDescription: 'C', description: 'c', timestamp: Date.now() },
          ],
        }),
      });
    });

    expect(document.querySelector('.stat-total .stat-value').textContent).toBe('3');
  });

  it('replaces issues entirely on a "clear" message', async () => {
    render(<App />);
    const ws = FakeWebSocket.instances[0];

    act(() => {
      ws.onmessage({
        data: JSON.stringify({
          type: 'issues',
          issues: [{ id: 1, severity: 'critical', elementDescription: 'A', description: 'a', timestamp: Date.now() }],
        }),
      });
    });
    expect(document.querySelector('.stat-total .stat-value').textContent).toBe('1');

    act(() => {
      ws.onmessage({ data: JSON.stringify({ type: 'clear' }) });
    });
    expect(document.querySelector('.stat-total .stat-value').textContent).toBe('0');
  });
});
