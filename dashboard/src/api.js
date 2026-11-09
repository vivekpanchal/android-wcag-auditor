export const API = '/api/v1';

const TOKEN_KEY = 'a11y_token';

export class AuthError extends Error {}

// The app opens the dashboard as /?t=<token>; keep it for this tab and clear it from the URL.
// Returns whether this tab is signed in. Runs before the first request, so the URL never leaks.
export function takeUrlToken() {
  const params = new URLSearchParams(location.search);
  if (params.has('t')) {
    if (params.get('t')) sessionStorage.setItem(TOKEN_KEY, params.get('t'));
    history.replaceState(null, '', location.pathname);
  }
  return Boolean(sessionStorage.getItem(TOKEN_KEY));
}

export async function authFetch(url, init = {}) {
  const token = sessionStorage.getItem(TOKEN_KEY);
  if (!token) throw new AuthError('no token');
  const res = await fetch(url, { ...init, headers: { ...init.headers, Authorization: `Bearer ${token}` } });
  if (res.status === 401) {
    sessionStorage.removeItem(TOKEN_KEY);
    throw new AuthError('unauthorized');
  }
  return res;
}

export function apiFetch(path, init) {
  return authFetch(API + path, init);
}

// EventSource cannot send headers, so the SSE stream is read by hand. Resolves when the stream
// ends and rejects when it fails; a 401 rejects with AuthError.
export async function openStream(signal, onOpen, onEvent) {
  const res = await apiFetch('/events', { signal });
  if (!res.ok) throw new Error(`/events ${res.status}`);
  onOpen();
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop(); // an unfinished line waits for the next chunk
    for (const line of lines) {
      if (line === '') {
        if (data.length) onEvent(event, data.join('\n'));
        event = 'message';
        data = [];
      } else if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).trimStart());
      }
    }
  }
}
