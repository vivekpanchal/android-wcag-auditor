import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App, { ScreenshotWithHighlight } from './App';
import { severityRank, filterIssues, groupIssues, parseFix } from './logic';

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

describe('ScreenshotWithHighlight', () => {
  it('positions the highlight box as a percentage of the image natural size', () => {
    const bounds = { x: 50, y: 25, width: 100, height: 50 };
    render(<ScreenshotWithHighlight src="/x.webp" bounds={bounds} alt="a screenshot" wrapClassName="wrap" imgClassName="thumb" />);

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
    render(<ScreenshotWithHighlight src="/x.webp" bounds={{ x: 0, y: 0, width: 10, height: 10 }} alt="a screenshot" />);
    expect(document.querySelector('.issue-highlight')).toBeNull();
  });

  it('renders no highlight when there are no bounds', () => {
    render(<ScreenshotWithHighlight src="/x.webp" bounds={null} alt="a screenshot" />);
    const img = screen.getByAltText('a screenshot');
    Object.defineProperty(img, 'naturalWidth', { value: 200, configurable: true });
    Object.defineProperty(img, 'naturalHeight', { value: 100, configurable: true });
    fireEvent.load(img);
    expect(document.querySelector('.issue-highlight')).toBeNull();
  });
});

// --- App-level: SSE handling ---

class FakeEventSource {
  static CLOSED = 2;
  static instances = [];
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.listeners = {};
    this.onopen = null;
    this.onerror = null;
    FakeEventSource.instances.push(this);
  }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  close() { this.readyState = FakeEventSource.CLOSED; }
  emit(type, data) {
    if (type === 'open') { this.readyState = 1; this.onopen?.(); return; }
    if (type === 'error') { this.onerror?.(); return; }
    (this.listeners[type] || []).forEach((fn) => fn({ data: JSON.stringify(data) }));
  }
}

const iss = (id, extra = {}) => ({ id, severity: 'minor', elementDescription: `E${id}`, description: `d${id}`, timestamp: 1, ...extra });
const total = () => document.querySelector('.stat-total .stat-value').textContent;
const es = () => FakeEventSource.instances[FakeEventSource.instances.length - 1];

// byPath values may be a value or a function returning a promise/value.
function setup(byPath = {}) {
  const defaults = {
    '/issues': [],
    '/control': { targetPackage: null, auditing: false },
    '/apps': [],
    '/auth': null,
    ...byPath,
  };
  const fetchMock = vi.fn((url) => {
    const path = Object.keys(defaults).find((p) => url === `/api/v1${p}`);
    const v = typeof defaults[path] === 'function' ? defaults[path]() : defaults[path];
    return Promise.resolve(v).then((body) =>
      body && body.__status ? { ok: false, status: body.__status } : { ok: true, status: 200, json: () => Promise.resolve(body) });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('App SSE handling', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    window.history.replaceState(null, '', '/');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('appends issue events and dedups by id', async () => {
    setup();
    render(<App />);
    await act(async () => {});
    act(() => { es().emit('issue', iss(1)); es().emit('issue', iss(2)); });
    expect(total()).toBe('2');
    act(() => { es().emit('issue', iss(2)); es().emit('issue', iss(3)); });
    expect(total()).toBe('3');
  });

  it('clear event empties the list', async () => {
    setup();
    render(<App />);
    await act(async () => {});
    act(() => es().emit('issue', iss(1)));
    expect(total()).toBe('1');
    act(() => es().emit('clear', {}));
    expect(total()).toBe('0');
  });

  it('control event updates the target display', async () => {
    setup();
    render(<App />);
    await act(async () => {});
    act(() => es().emit('control', { targetPackage: 'com.example.wellness', auditing: true }));
    expect(document.querySelector('.target-app-name')).toHaveTextContent('com.example.wellness');
    expect(screen.getByRole('button', { name: /stop auditing/i })).toBeInTheDocument();
  });

  it('shows a prompt to pick a target on the phone, and disables Start, when none is set yet', async () => {
    setup();
    render(<App />);
    await act(async () => {});
    expect(screen.getByText(/choose one in the Auditor app on your phone/i)).toBeTruthy();
    expect(screen.getByText('Pick a target app on your phone, then hit Start.')).toBeTruthy();
    expect(screen.getByRole('button', { name: /start auditing/i })).toBeDisabled();
  });

  it('enables Start once a target is set, with no device gate', async () => {
    setup({
      '/control': { targetPackage: 'com.example.wellness', auditing: false },
      '/apps': [{ appName: 'Wellness', packageName: 'com.example.wellness' }],
    });
    render(<App />);
    await act(async () => {});
    expect(screen.getByText('Wellness')).toBeTruthy();
    expect(screen.getByText('Not auditing — hit Start when ready.')).toBeTruthy();
    expect(screen.getByRole('button', { name: /start auditing/i })).not.toBeDisabled();
  });

  it('shows the reconnect banner while the stream is down and clears it on open', async () => {
    setup();
    render(<App />);
    await act(async () => {});
    expect(screen.getByRole('status')).toHaveTextContent('');
    act(() => es().emit('error'));
    expect(screen.getByRole('status')).toHaveTextContent('Device disconnected — reconnecting…');
    await act(async () => es().emit('open'));
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  it('refetches issues when the stream reopens', async () => {
    let calls = 0;
    const fetchMock = setup({ '/issues': () => (++calls === 1 ? [] : [iss(7)]) });
    render(<App />);
    await act(async () => {});
    act(() => es().emit('error'));
    await act(async () => es().emit('open'));
    expect(fetchMock.mock.calls.filter(([u]) => u === '/api/v1/issues')).toHaveLength(2);
    expect(screen.getByText('E7')).toBeTruthy();
  });

  it('posts the ?t= token to /api/v1/auth and strips it from the URL', async () => {
    const fetchMock = setup();
    window.history.replaceState(null, '', '/?t=abc');
    render(<App />);
    await act(async () => {});
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth', expect.objectContaining({ method: 'POST', body: '{"token":"abc"}' }));
    expect(window.location.search).toBe('');
  });

  it('connects the stream only after auth', async () => {
    let release;
    setup({ '/auth': () => new Promise((r) => { release = r; }) });
    window.history.replaceState(null, '', '/?t=abc');
    render(<App />);
    await act(async () => {});
    expect(FakeEventSource.instances).toHaveLength(0);
    await act(async () => release(null));
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('shows the sign-in message on 401 and does not recreate the stream', async () => {
    vi.useFakeTimers();
    setup({ '/issues': { __status: 401 } });
    render(<App />);
    await act(async () => {});
    expect(screen.getByText(/Not signed in to this device's dashboard/)).toBeTruthy();
    expect(screen.getByRole('status')).toHaveTextContent('');
    const before = FakeEventSource.instances.length;
    act(() => { es().readyState = FakeEventSource.CLOSED; es().emit('error'); });
    await act(async () => { vi.advanceTimersByTime(2000); });
    expect(FakeEventSource.instances).toHaveLength(before);
  });

  it('recreates the stream after 2s when the browser gave up and auth is fine', async () => {
    vi.useFakeTimers();
    setup();
    render(<App />);
    await act(async () => {});
    act(() => { es().readyState = FakeEventSource.CLOSED; es().emit('error'); });
    await act(async () => {}); // the auth probe settles before the retry timer starts
    await act(async () => { vi.advanceTimersByTime(2000); });
    expect(FakeEventSource.instances).toHaveLength(2);
  });

  it('keeps an issue that arrives during the refetch', async () => {
    let release;
    let calls = 0;
    setup({ '/issues': () => (++calls === 1 ? [] : new Promise((r) => { release = r; })) });
    render(<App />);
    await act(async () => {});
    await act(async () => es().emit('open'));
    act(() => es().emit('issue', iss(5)));
    await act(async () => release([iss(6)]));
    expect(screen.getByText('E5')).toBeTruthy();
    expect(screen.getByText('E6')).toBeTruthy();
  });

  it('a clear during the refetch discards the stale response', async () => {
    let release;
    let calls = 0;
    setup({ '/issues': () => (++calls === 1 ? [] : new Promise((r) => { release = r; })) });
    render(<App />);
    await act(async () => {});
    await act(async () => es().emit('open'));
    act(() => es().emit('clear', {}));
    await act(async () => release([iss(9)]));
    expect(total()).toBe('0');
  });

  it('shows the sign-in message when the stream 401s after a good load', async () => {
    vi.useFakeTimers();
    let ctl = { targetPackage: null, auditing: false };
    setup({ '/control': () => ctl });
    render(<App />);
    await act(async () => {});
    ctl = { __status: 401 };
    const before = FakeEventSource.instances.length;
    act(() => { es().readyState = FakeEventSource.CLOSED; es().emit('error'); });
    await act(async () => { vi.advanceTimersByTime(2000); });
    expect(screen.getByText(/Not signed in to this device's dashboard/)).toBeTruthy();
    expect(FakeEventSource.instances).toHaveLength(before);
  });

  it('a 500 on /apps does not blank the page and shows the failure message', async () => {
    setup({ '/apps': { __status: 500 } });
    render(<App />);
    await act(async () => {});
    expect(screen.getByRole('button', { name: /start auditing/i })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Request failed — try again.');
  });

  it('a failed DELETE keeps the issues', async () => {
    const base = setup({ '/issues': [iss(1)] });
    vi.stubGlobal('fetch', vi.fn((url, init) =>
      init?.method === 'DELETE' ? Promise.resolve({ ok: false, status: 500 }) : base(url, init)));
    render(<App />);
    await act(async () => {});
    expect(total()).toBe('1');
    fireEvent.click(screen.getByRole('button', { name: /clear session/i }));
    await act(async () => {});
    expect(total()).toBe('1');
    expect(screen.getByRole('status')).toHaveTextContent('Request failed — try again.');
  });

  it('discards an older refetch that resolves after a newer one', async () => {
    const releases = [];
    setup({ '/issues': () => new Promise((r) => releases.push(r)) });
    render(<App />);
    await act(async () => {});
    await act(async () => es().emit('open'));
    expect(releases).toHaveLength(2);
    await act(async () => releases[1]([iss(2)]));
    await act(async () => releases[0]([iss(1)]));
    expect(screen.getByText('E2')).toBeTruthy();
    expect(screen.queryByText('E1')).toBeNull();
  });

  it('renders screenshots from screenshotUrl', async () => {
    setup({ '/issues': [iss(1, { screenshotUrl: '/api/v1/screenshots/a.webp' })] });
    render(<App />);
    await act(async () => {});
    expect(document.querySelector('img.issue-thumb').src).toMatch(/\/api\/v1\/screenshots\/a\.webp$/);
  });
});

describe('parseFix', () => {
  it('parses the structured fix JSON from the device', () => {
    const raw = JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'compose' });
    expect(parseFix(raw)).toEqual({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'compose' });
  });

  it('treats legacy plain text as the summary', () => {
    expect(parseFix('Add a label')).toEqual({ summary: 'Add a label' });
  });

  it('drops non-string fields so a hostile payload cannot crash rendering', () => {
    const raw = JSON.stringify({ summary: 'S', views: { a: 1 }, compose: 'C', docUrl: 42, framework: 'compose' });
    expect(parseFix(raw)).toEqual({ summary: 'S', compose: 'C', framework: 'compose' });
  });

  it('treats a non-string summary as unparseable', () => {
    const raw = JSON.stringify({ summary: { a: 1 } });
    expect(parseFix(raw)).toEqual({ summary: raw });
  });

  it('returns null for missing fixes', () => {
    expect(parseFix(null)).toBeNull();
    expect(parseFix('')).toBeNull();
  });
});

describe('App fix guidance', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    setup();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const showIssue = async (suggestedFix) => {
    render(<App />);
    await act(async () => {});
    act(() => {
      es().emit('issue', { id: 1, timestamp: 1, severity: 'serious', elementDescription: 'Btn', description: 'd', suggestedFix });
    });
  };

  it('shows the detected framework snippet first and links https docs', async () => {
    await showIssue(JSON.stringify({
      summary: 'Make it 48dp', views: 'android:minHeight="48dp"', compose: 'Modifier.minimumInteractiveComponentSize()',
      docUrl: 'https://developer.android.com/guide/topics/ui/accessibility/apps', framework: 'compose',
    }));

    expect(screen.getByText(/How to fix: Make it 48dp/)).toBeInTheDocument();
    const labels = [...document.querySelectorAll('.fix-snippet-label')].map((el) => el.textContent);
    expect(labels).toEqual(['Compose (detected)', 'Views']);
    expect(screen.getByRole('link', { name: 'Android docs' })).toHaveAttribute('href', 'https://developer.android.com/guide/topics/ui/accessibility/apps');
  });

  it('puts Views first when the element is a View', async () => {
    await showIssue(JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'views' }));
    const labels = [...document.querySelectorAll('.fix-snippet-label')].map((el) => el.textContent);
    expect(labels).toEqual(['Views (detected)', 'Compose']);
  });

  it('never renders a non-https doc link', async () => {
    await showIssue(JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'javascript:alert(1)', framework: 'views' }));
    expect(screen.queryByRole('link', { name: 'Android docs' })).toBeNull();
  });

  it('shows a legacy plain-text fix as the summary', async () => {
    await showIssue('Add a content description');
    expect(screen.getByText(/How to fix: Add a content description/)).toBeInTheDocument();
  });
});
