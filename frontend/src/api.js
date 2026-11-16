// All calls are relative: Vite proxies /api to Spring Boot in dev,
// and in the packaged jar the UI is served by Spring Boot itself.
const BASE = '/api/connections';

/** Thrown when the backend no longer knows the connection (expired / server restarted). */
export class SessionExpiredError extends Error {
  constructor(message) {
    super(message || 'Your database session has expired. Please connect again.');
    this.name = 'SessionExpiredError';
  }
}

async function failure(res) {
  let body = null;
  try {
    body = await res.json();
  } catch {
    /* not JSON */
  }
  if (body?.code === 'SESSION_EXPIRED') return new SessionExpiredError(body.message);
  return new Error(body?.message || `Request failed (${res.status} ${res.statusText})`);
}

function query(params) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') q.set(k, v);
  });
  return q.toString();
}

const tablesUrl = (id) => `${BASE}/${encodeURIComponent(id)}/tables`;

/** Opens a connection. Returns { id, info }. */
export async function connect(form) {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...form, port: form.port ? Number(form.port) : null }),
  });
  if (!res.ok) throw await failure(res);
  return res.json();
}

export async function disconnect(id) {
  try {
    await fetch(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  } catch {
    /* backend down – nothing to close */
  }
}

/** Checks that a saved session is still alive; returns its info. */
export async function fetchConnection(id) {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`);
  if (!res.ok) throw await failure(res);
  return res.json();
}

export async function fetchTables(id) {
  const res = await fetch(tablesUrl(id));
  if (!res.ok) throw await failure(res);
  return res.json();
}

export async function fetchDetails(id, table) {
  const res = await fetch(`${tablesUrl(id)}/details?${query({ schema: table.schema ?? table.catalog, table: table.name })}`);
  if (!res.ok) throw await failure(res);
  return res.json();
}

function fileNameFrom(res, fallback) {
  const cd = res.headers.get('Content-Disposition') || '';
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (utf8) return decodeURIComponent(utf8[1]);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1] : fallback;
}

/**
 * Downloads every row and column of the table and saves it as a file.
 * onProgress(bytesReceived) is called while the data streams in.
 */
export async function downloadTable(id, table, format, onProgress, signal) {
  const url = `${tablesUrl(id)}/download?${query({
    schema: table.schema ?? table.catalog,
    table: table.name,
    format,
  })}`;
  const res = await fetch(url, { signal });
  if (!res.ok) throw await failure(res);

  const reader = res.body.getReader();
  const chunks = [];
  let received = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    received += value.length;
    onProgress?.(received);
  }

  const blob = new Blob(chunks, { type: res.headers.get('Content-Type') || 'application/octet-stream' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = fileNameFrom(res, `${table.name}.${format}`);
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
  return received;
}
