export const API = '/api/v1';

export class AuthError extends Error {}

export async function apiFetch(path, init) {
  const res = await fetch(API + path, init);
  if (res.status === 401) throw new AuthError('unauthorized');
  return res;
}

// The phone opens the dashboard as /?t=<token>; trade it for the session cookie, then hide it.
export async function authFromUrl() {
  const token = new URLSearchParams(location.search).get('t');
  if (!token) return;
  try {
    await fetch(`${API}/auth`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
  } catch {
    // a failed sign-in surfaces as a 401 on the first API call
  } finally {
    history.replaceState(null, '', location.pathname);
  }
}
