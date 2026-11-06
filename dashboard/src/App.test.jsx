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

// --- App-level: event stream handling ---

const TOKEN = 'fake-token';

class FakeStream {
  static instances = [];
  constructor(signal) {
    this.signal = signal;
    this.body = new ReadableStream({ start: (c) => { this.ctrl = c; } });
    this.response = new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
    FakeStream.instances.push(this);
  }
  open() { this.resolve({ ok: true, status: 200, body: this.body }); }
  refuse(status) { this.resolve({ ok: false, status }); }
  chunk(bytes) { this.ctrl.enqueue(typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes); }
  emit(type, data) { this.chunk(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`); }
  end() { this.ctrl.close(); }
  fail() { this.ctrl.error(new Error('stream lost')); }
}

const iss = (id, extra = {}) => ({ id, severity: 'minor', elementDescription: `E${id}`, description: `d${id}`, timestamp: 1, ...extra });
const total = () => document.querySelector('.stat-total .stat-value').textContent;
const es = () => FakeStream.instances[FakeStream.instances.length - 1];
// Stream reads settle over several microtasks; this works under fake timers too.
const flush = () => act(async () => { for (let i = 0; i < 30; i += 1) await Promise.resolve(); });
const tick = async (ms) => { await act(async () => { vi.advanceTimersByTime(ms); }); await flush(); };
const mount = async () => {
  const view = render(<App />);
  await flush();
  es().open();
  await flush();
  return view;
};
const send = async (fn) => { fn(es()); await flush(); };

// byPath values may be a value or a function returning a promise/value.
function setup(byPath = {}) {
  const defaults = {
    '/issues': [],
    '/control': { targetPackage: null, auditing: false },
    '/apps': [],
    ...byPath,
  };
  const fetchMock = vi.fn((url, init) => {
    if (url === '/api/v1/events') return new FakeStream(init.signal).response;
    const path = Object.keys(defaults).find((p) => url === `/api/v1${p}`);
    const v = typeof defaults[path] === 'function' ? defaults[path]() : defaults[path];
    return Promise.resolve(v).then((body) =>
      body && body.__status
        ? { ok: false, status: body.__status }
        : { ok: true, status: 200, json: () => Promise.resolve(body), blob: () => Promise.resolve(new Blob(['x'])) });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('App stream handling', () => {
  beforeEach(() => {
    FakeStream.instances = [];
    sessionStorage.setItem('a11y_token', TOKEN);
    window.history.replaceState(null, '', '/');
  });
  afterEach(() => {
    sessionStorage.clear();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('appends issue events and dedups by id', async () => {
    setup();
    await mount();
    await send((s) => { s.emit('issue', iss(1)); s.emit('issue', iss(2)); });
    expect(total()).toBe('2');
    await send((s) => { s.emit('issue', iss(2)); s.emit('issue', iss(3)); });
    expect(total()).toBe('3');
  });

  it('parses a frame split across chunks, even inside a character', async () => {
    setup();
    await mount();
    const frame = new TextEncoder().encode(`event: issue\ndata: ${JSON.stringify(iss(1, { elementDescription: 'Café' }))}\n\n`);
    const cut = frame.indexOf(0xc3) + 1; // between the two bytes of the é
    await send((s) => s.chunk(frame.slice(0, 4)));
    await send((s) => s.chunk(frame.slice(4, cut)));
    expect(total()).toBe('0');
    await send((s) => s.chunk(frame.slice(cut)));
    expect(screen.getByText('Café')).toBeTruthy();
  });

  it('parses several frames in one chunk and ignores comment lines', async () => {
    setup();
    await mount();
    await send((s) => s.chunk(`: connected\n\nevent: issue\ndata: ${JSON.stringify(iss(1))}\n\n: ping\n\nevent: issue\ndata: ${JSON.stringify(iss(2))}\n\n`));
    expect(total()).toBe('2');
  });

  it('clear event empties the list', async () => {
    setup();
    await mount();
    await send((s) => s.emit('issue', iss(1)));
    expect(total()).toBe('1');
    await send((s) => s.emit('clear', {}));
    expect(total()).toBe('0');
  });

  it('control event updates the target display', async () => {
    setup();
    await mount();
    await send((s) => s.emit('control', { targetPackage: 'com.example.wellness', auditing: true }));
    expect(document.querySelector('.target-app-name')).toHaveTextContent('com.example.wellness');
    expect(screen.getByRole('button', { name: /stop auditing/i })).toBeInTheDocument();
  });

  it('shows a prompt to pick a target on the phone, and disables Start, when none is set yet', async () => {
    setup();
    await mount();
    expect(screen.getByText(/choose one in the Auditor app on your phone/i)).toBeTruthy();
    expect(screen.getByText('Pick a target app on your phone, then hit Start.')).toBeTruthy();
    expect(screen.getByRole('button', { name: /start auditing/i })).toBeDisabled();
  });

  it('enables Start once a target is set, with no device gate', async () => {
    setup({
      '/control': { targetPackage: 'com.example.wellness', auditing: false },
      '/apps': [{ appName: 'Wellness', packageName: 'com.example.wellness' }],
    });
    await mount();
    expect(screen.getByText('Wellness')).toBeTruthy();
    expect(screen.getByText('Not auditing — hit Start when ready.')).toBeTruthy();
    expect(screen.getByRole('button', { name: /start auditing/i })).not.toBeDisabled();
  });

  it('shows the reconnect banner while the stream is down and clears it on open', async () => {
    vi.useFakeTimers();
    setup();
    await mount();
    expect(screen.getByRole('status')).toHaveTextContent('');
    await send((s) => s.end());
    expect(screen.getByRole('status')).toHaveTextContent('Device disconnected — reconnecting…');
    await tick(2000);
    await send((s) => s.open());
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  it('refetches issues when the stream reopens', async () => {
    vi.useFakeTimers();
    let calls = 0;
    const fetchMock = setup({ '/issues': () => (++calls < 3 ? [] : [iss(7)]) });
    await mount();
    expect(fetchMock.mock.calls.filter(([u]) => u === '/api/v1/issues')).toHaveLength(2); // load, open
    await send((s) => s.fail());
    await tick(2000);
    await send((s) => s.open());
    expect(fetchMock.mock.calls.filter(([u]) => u === '/api/v1/issues')).toHaveLength(3);
    expect(screen.getByText('E7')).toBeTruthy();
  });

  it('stores the ?t= token and strips it from the URL before any request', async () => {
    sessionStorage.clear();
    const fetchMock = setup();
    window.history.replaceState(null, '', '/?t=abc');
    let searchAtFirstFetch;
    fetchMock.mockImplementationOnce(() => {
      searchAtFirstFetch = window.location.search;
      return new Promise(() => {});
    });
    render(<App />);
    expect(window.location.search).toBe('');
    expect(sessionStorage.getItem('a11y_token')).toBe('abc');
    await flush();
    expect(searchAtFirstFetch).toBe('');
  });

  it('a new ?t= replaces a stored token, and an empty one is still stripped', () => {
    setup();
    window.history.replaceState(null, '', '/?t=newer');
    const { unmount } = render(<App />);
    expect(sessionStorage.getItem('a11y_token')).toBe('newer');
    unmount();
    window.history.replaceState(null, '', '/?t=');
    render(<App />);
    expect(window.location.search).toBe('');
    expect(sessionStorage.getItem('a11y_token')).toBe('newer');
  });

  it('stays signed in on reload, sending the stored token only as a header', async () => {
    const fetchMock = setup();
    await mount();
    expect(fetchMock.mock.calls.length).toBeGreaterThan(3);
    for (const [url, init] of fetchMock.mock.calls) {
      expect(new Headers(init.headers).get('Authorization')).toBe(`Bearer ${TOKEN}`);
      expect(url).not.toContain(TOKEN);
    }
  });

  it('with no token shows sign-in and sends no request', async () => {
    sessionStorage.clear();
    const fetchMock = setup();
    render(<App />);
    expect(screen.getByText(/Not signed in to this device's dashboard/)).toBeTruthy();
    await flush();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(FakeStream.instances).toHaveLength(0);
  });

  it('shows the sign-in message on 401, drops the token and does not recreate the stream', async () => {
    vi.useFakeTimers();
    setup({ '/issues': { __status: 401 } });
    render(<App />);
    await flush();
    expect(screen.getByText(/Not signed in to this device's dashboard/)).toBeTruthy();
    expect(screen.getByRole('status')).toHaveTextContent('');
    expect(sessionStorage.getItem('a11y_token')).toBeNull();
    const before = FakeStream.instances.length;
    await send((s) => s.refuse(401));
    await tick(2000);
    expect(FakeStream.instances).toHaveLength(before);
  });

  it('recreates the stream after 2s when it ends and auth is fine', async () => {
    vi.useFakeTimers();
    setup();
    await mount();
    await send((s) => s.end());
    await tick(1999);
    expect(FakeStream.instances).toHaveLength(1);
    await tick(1);
    expect(FakeStream.instances).toHaveLength(2);
  });

  it('retries after 2s when connecting fails or the server errors', async () => {
    vi.useFakeTimers();
    setup();
    render(<App />);
    await flush();
    await send((s) => s.reject(new TypeError('failed to fetch')));
    expect(screen.getByRole('status')).toHaveTextContent('Device disconnected — reconnecting…');
    await tick(2000);
    await send((s) => s.refuse(500));
    await tick(2000);
    expect(FakeStream.instances).toHaveLength(3);
  });

  it('aborts the stream on unmount and does not retry', async () => {
    vi.useFakeTimers();
    setup();
    const { unmount } = await mount();
    const stream = es();
    unmount();
    expect(stream.signal.aborted).toBe(true);
    stream.fail();
    await tick(2000);
    expect(FakeStream.instances).toHaveLength(1);
  });

  it('keeps an issue that arrives during the refetch', async () => {
    let release;
    let calls = 0;
    setup({ '/issues': () => (++calls === 1 ? [] : new Promise((r) => { release = r; })) });
    await mount();
    await send((s) => s.emit('issue', iss(5)));
    await act(async () => release([iss(6)]));
    expect(screen.getByText('E5')).toBeTruthy();
    expect(screen.getByText('E6')).toBeTruthy();
  });

  it('a clear during the refetch discards the stale response', async () => {
    let release;
    let calls = 0;
    setup({ '/issues': () => (++calls === 1 ? [] : new Promise((r) => { release = r; })) });
    await mount();
    await send((s) => s.emit('clear', {}));
    await act(async () => release([iss(9)]));
    expect(total()).toBe('0');
  });

  it('shows the sign-in message when the stream 401s after a good load', async () => {
    vi.useFakeTimers();
    setup();
    await mount();
    await send((s) => s.end());
    await tick(2000);
    const before = FakeStream.instances.length;
    await send((s) => s.refuse(401));
    await tick(2000);
    expect(screen.getByText(/Not signed in to this device's dashboard/)).toBeTruthy();
    expect(FakeStream.instances).toHaveLength(before);
  });

  it('a 500 on /apps does not blank the page and shows the failure message', async () => {
    setup({ '/apps': { __status: 500 } });
    render(<App />);
    await flush();
    expect(screen.getByRole('button', { name: /start auditing/i })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Request failed — try again.');
  });

  it('a failed DELETE keeps the issues', async () => {
    const base = setup({ '/issues': [iss(1)] });
    vi.stubGlobal('fetch', vi.fn((url, init) =>
      init?.method === 'DELETE' ? Promise.resolve({ ok: false, status: 500 }) : base(url, init)));
    await mount();
    expect(total()).toBe('1');
    fireEvent.click(screen.getByRole('button', { name: /clear session/i }));
    await flush();
    expect(total()).toBe('1');
    expect(screen.getByRole('status')).toHaveTextContent('Request failed — try again.');
  });

  it('discards an older refetch that resolves after a newer one', async () => {
    const releases = [];
    setup({ '/issues': () => new Promise((r) => releases.push(r)) });
    await mount();
    expect(releases).toHaveLength(2);
    await act(async () => releases[1]([iss(2)]));
    await act(async () => releases[0]([iss(1)]));
    expect(screen.getByText('E2')).toBeTruthy();
    expect(screen.queryByText('E1')).toBeNull();
  });

  describe('fresh issues', () => {
    const exportBlob = () => URL.createObjectURL.mock.calls[0][0];
    const readBlob = (blob) => new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.readAsText(blob);
    });
    beforeEach(() => {
      URL.createObjectURL = vi.fn(() => 'blob:report');
      URL.revokeObjectURL = vi.fn();
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    });
    afterEach(() => vi.restoreAllMocks());

    it.each([
      ['Export CSV', 'E2'],
      ['Export HTML', 'E2'],
    ])('%s fetches /issues and exports what the device has now', async (name, missed) => {
      let list = [iss(1)];
      setup({ '/issues': () => list });
      await mount();
      list = [iss(1), iss(2)]; // recorded while the stream was not delivering
      expect(total()).toBe('1');
      fireEvent.click(screen.getByRole('button', { name }));
      await flush();
      await vi.waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(1));
      expect(await readBlob(exportBlob())).toContain(missed);
    });

    it.each(['Export CSV', 'Export HTML'])('%s writes nothing when the fetch fails', async (name) => {
      let list = [iss(1)];
      setup({ '/issues': () => list });
      await mount();
      list = { __status: 500 };
      fireEvent.click(screen.getByRole('button', { name }));
      await flush();
      expect(URL.createObjectURL).not.toHaveBeenCalled();
      expect(screen.getByRole('status')).toHaveTextContent('Request failed — try again.');
    });

    it('refetches issues and control when the tab becomes visible, not when hidden', async () => {
      let list = [];
      let ctl = { targetPackage: null, auditing: false };
      setup({ '/issues': () => list, '/control': () => ctl });
      await mount();
      const setVisibility = async (state) => {
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);
        document.dispatchEvent(new Event('visibilitychange'));
        await flush();
      };
      list = [iss(4)];
      ctl = { targetPackage: 'com.ex', auditing: false };
      await setVisibility('hidden');
      expect(total()).toBe('0');
      await setVisibility('visible');
      expect(total()).toBe('1');
      expect(document.querySelector('.target-app-name')).toHaveTextContent('com.ex');
    });

    it('stops refetching on visibility after unmount', async () => {
      const fetchMock = setup();
      const { unmount } = await mount();
      unmount();
      const before = fetchMock.mock.calls.length;
      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
      document.dispatchEvent(new Event('visibilitychange'));
      await flush();
      expect(fetchMock.mock.calls.length).toBe(before);
    });
  });

  describe('screenshots', () => {
    const shot = '/api/v1/screenshots/a.webp';
    beforeEach(() => {
      URL.createObjectURL = vi.fn(() => 'blob:shot');
      URL.revokeObjectURL = vi.fn();
    });

    it('fetches with the header and shows a blob URL, in the card and the lightbox', async () => {
      const fetchMock = setup({ '/issues': [iss(1, { screenshotUrl: shot })], '/screenshots/a.webp': {} });
      await mount();
      const call = fetchMock.mock.calls.find(([u]) => u === shot);
      expect(new Headers(call[1].headers).get('Authorization')).toBe(`Bearer ${TOKEN}`);
      expect(document.querySelector('img.issue-thumb').getAttribute('src')).toBe('blob:shot');
      fireEvent.click(document.querySelector('.issue-thumb-wrap'));
      await flush();
      expect(document.querySelector('.lightbox img').getAttribute('src')).toBe('blob:shot');
    });

    it('revokes the blob URL on unmount', async () => {
      setup({ '/issues': [iss(1, { screenshotUrl: shot })], '/screenshots/a.webp': {} });
      const { unmount } = await mount();
      expect(URL.revokeObjectURL).not.toHaveBeenCalled();
      unmount();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:shot');
    });

    it('renders no image when the fetch fails', async () => {
      setup({ '/issues': [iss(1, { screenshotUrl: shot })], '/screenshots/a.webp': { __status: 500 } });
      await mount();
      expect(screen.getByText('E1')).toBeTruthy();
      expect(document.querySelector('img.issue-thumb')).toBeNull();
    });
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
    FakeStream.instances = [];
    sessionStorage.setItem('a11y_token', TOKEN);
    setup();
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  const showIssue = async (suggestedFix) => {
    await mount();
    await send((s) => s.emit('issue', { id: 1, timestamp: 1, severity: 'serious', elementDescription: 'Btn', description: 'd', suggestedFix }));
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
