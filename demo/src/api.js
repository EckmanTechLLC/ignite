/**
 * Ignite API client.
 *
 * In production the gateway injects window.__IGNITE_ENV__ into the HTML
 * before serving it.  In local dev, set VITE_GATEWAY_URL and VITE_API_KEY
 * in a .env.local file and the Vite dev server will proxy/CORS as needed.
 */

const IGN = (typeof window !== 'undefined' && window.__IGNITE_ENV__) || {};

export const GATEWAY_URL = (
  IGN.IGNITE_GATEWAY_URL ||
  import.meta.env.VITE_GATEWAY_URL ||
  ''
).replace(/\/$/, '');

export const API_KEY =
  IGN.IGNITE_API_KEY || import.meta.env.VITE_API_KEY || '';

const API_BASE = `${GATEWAY_URL}/data/ignite`;

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

function buildUrl(path, params = {}) {
  const qs = new URLSearchParams(params).toString();
  return `${API_BASE}${path}${qs ? '?' + qs : ''}`;
}

function baseHeaders() {
  const token = localStorage.getItem('ignite_token');
  return {
    ...(IGN.IGNITE_SITE ? { 'X-Ignite-Site': IGN.IGNITE_SITE } : {}),
    ...(API_KEY ? { 'X-Ignite-Key': API_KEY } : {}),
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
  };
}

async function failResponse(res) {
  if (res.status === 401 && localStorage.getItem('ignite_token')) {
    localStorage.removeItem('ignite_token');
    window.dispatchEvent(new Event('ignite:unauthorized'));
  }
  const text = await res.text().catch(() => '');
  throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
}

async function fetchJson(url) {
  const res = await fetch(url, {
    credentials: 'include',
    headers: { ...baseHeaders() },
  });
  if (!res.ok) await failResponse(res);
  return res.json();
}

async function postJson(url, body, extraHeaders = {}) {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...baseHeaders(), ...extraHeaders },
    body: JSON.stringify(body),
  });
  if (!res.ok) await failResponse(res);
  return res.json();
}

// -------------------------------------------------------------------------
// Tag API
// -------------------------------------------------------------------------

/** Browse tag tree children at path (empty string = root). */
export function browseTags(path = '') {
  const params = {};
  if (path) params.path = path;
  return fetchJson(buildUrl('/tags/browse', params));
}

/** Read current values for one or more tag paths. */
export function readTags(paths) {
  return fetchJson(buildUrl('/tags/read', { paths: paths.join(',') }));
}

/** Query tag history for a time range. */
export function getHistory(paths, start, end, returnSize = 100) {
  return fetchJson(buildUrl('/tags/history', {
    paths: paths.join(','),
    start: start.toISOString(),
    end: end.toISOString(),
    returnSize: String(returnSize),
  }));
}

// -------------------------------------------------------------------------
// SSE subscription
// -------------------------------------------------------------------------

/**
 * Open an SSE stream subscribing to the given tag paths.
 * Returns the EventSource so the caller can close() it on cleanup.
 *
 * onEvent receives parsed JSON event objects:
 *   { type: 'tagChange', path, value, quality, timestamp }
 *
 * onStatusChange receives one of: 'connecting' | 'connected' | 'error'
 */
export function openSse(paths, onEvent, onStatusChange) {
  const params = {};
  if (API_KEY) params.apiKey = API_KEY;
  const token = localStorage.getItem('ignite_token');
  if (token) params.token = token;
  if (IGN.IGNITE_SITE) params.site = IGN.IGNITE_SITE;
  if (paths.length) params.paths = paths.join(',');

  const qs = new URLSearchParams(params).toString();
  const url = `${API_BASE}/sse${qs ? '?' + qs : ''}`;

  onStatusChange('connecting');
  const es = new EventSource(url, { withCredentials: true });

  es.onopen = () => onStatusChange('connected');

  es.onmessage = (e) => {
    try {
      const event = JSON.parse(e.data);
      onEvent(event);
    } catch (_) {
      // ignore malformed frames
    }
  };

  es.onerror = () => {
    // CLOSED means the server refused (e.g. 401/403) and no retry will happen
    onStatusChange(es.readyState === EventSource.CLOSED ? 'disconnected' : 'error');
  };

  return es;
}

// -------------------------------------------------------------------------
// Alarm API
// -------------------------------------------------------------------------

/** Get active alarm status with optional filters. */
export function getAlarmStatus(filters = {}) {
  return fetchJson(buildUrl('/alarms/status', filters));
}

/** Get alarm history with optional filters. */
export function getAlarmHistory(filters = {}) {
  return fetchJson(buildUrl('/alarms/history', filters));
}

/** Write one or more tag values. writes: [{path, value}, ...] */
export function writeTags(writes) {
  return postJson(buildUrl('/tags/write'), writes);
}

/** Acknowledge an alarm by ID. */
export function acknowledgeAlarm(id) {
  return postJson(buildUrl('/alarms/acknowledge'), { id });
}

// -------------------------------------------------------------------------
// Auth API
// -------------------------------------------------------------------------

/** Get the current session and permissions for the logged-in user. */
export function getSession(site) {
  const params = site ? { site } : {};
  return fetchJson(buildUrl('/auth/session', params));
}

/** Authenticate with username/password, returns {token, user, roles, expiresIn}. */
export function login(username, password) {
  return postJson(buildUrl('/auth/login'), { username, password });
}

/** Invalidate the current Bearer token session on the server. */
export function logout() {
  const token = localStorage.getItem('ignite_token');
  if (!token) return Promise.resolve();
  return postJson(buildUrl('/auth/logout'), {}, { 'Authorization': `Bearer ${token}` });
}

// -------------------------------------------------------------------------
// System API
// -------------------------------------------------------------------------

// -------------------------------------------------------------------------
// Audit API
// -------------------------------------------------------------------------

/**
 * Query audit log entries.
 * @param {Object} params - { auditProfile, startDate, endDate, actor, action, target }
 * All params optional except auditProfile.
 * startDate/endDate as epoch millis.
 */
export function getAuditLogs(params = {}) {
  const query = {};
  Object.entries(params).forEach(([k, v]) => {
    if (v != null && v !== '') query[k] = v;
  });
  return fetchJson(buildUrl('/audit/logs', query));
}

// -------------------------------------------------------------------------
// System API
// -------------------------------------------------------------------------

/** Get gateway system info. */
export function getSystemInfo() {
  return fetchJson(buildUrl('/system/info'));
}

/** Get database connections. */
export function getDbConnections() {
  return fetchJson(buildUrl('/db/connections'));
}

// -------------------------------------------------------------------------
// Script API
// -------------------------------------------------------------------------

/** List Ignition projects available for script execution. */
export function getProjects() {
  return fetchJson(buildUrl('/script/projects'));
}

/** Execute a project library function by dot-path (e.g. demo.helloWorld). */
export function executeLibraryScript(project, path, args = [], kwargs = {}) {
  return postJson(buildUrl('/script/library'), { project, path, args, kwargs });
}

/** Execute a system.* function by dot-path (e.g. system.tag.readBlocking). */
export function executeSystemFunction(project, func, args = [], kwargs = {}) {
  return postJson(buildUrl('/script/system'), { project, function: func, args, kwargs });
}
