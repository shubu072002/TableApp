import { useState } from 'react';
import { connect } from '../api.js';
import {
  DatabaseIcon,
  EyeIcon,
  EyeOffIcon,
  PlugIcon,
  AlertIcon,
  InfoIcon,
  LockIcon,
} from '../icons.jsx';

export const DB_TYPES = {
  postgres: {
    label: 'PostgreSQL',
    port: 5432,
    dbLabel: 'Database',
    dbPlaceholder: 'e.g. taskdb',
    schemaHint: 'Default: public',
    defaults: { host: 'localhost', database: 'taskdb', username: 'postgres' },
  },
  oracle: {
    label: 'Oracle',
    port: 1521,
    dbLabel: 'Service name',
    dbPlaceholder: 'e.g. ORCL or XEPDB1',
    schemaHint: 'Default: your username (e.g. HR)',
    defaults: { host: 'localhost', database: 'ORCL', username: '' },
  },
  mysql: {
    label: 'MySQL',
    port: 3306,
    dbLabel: 'Database',
    dbPlaceholder: 'e.g. shop',
    schemaHint: 'Not used for MySQL',
    defaults: { host: 'localhost', database: '', username: 'root' },
  },
};

const STORE_KEY = 'tableapp.connection.';

/** Last-used details per database type – never includes the password. */
function loadSaved(type) {
  const base = { ...DB_TYPES[type].defaults, port: String(DB_TYPES[type].port), schema: '', oracleMode: 'service' };
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY + type) || 'null');
    return saved ? { ...base, ...saved } : base;
  } catch {
    return base;
  }
}

function save(type, form) {
  const { password, ...rest } = form; // eslint-disable-line no-unused-vars
  try {
    localStorage.setItem(STORE_KEY + type, JSON.stringify(rest));
    localStorage.setItem(STORE_KEY + 'lastType', type);
  } catch {
    /* storage blocked – just not remembered */
  }
}

function initialType() {
  try {
    const t = localStorage.getItem(STORE_KEY + 'lastType');
    if (t && DB_TYPES[t]) return t;
  } catch {
    /* ignore */
  }
  return 'postgres';
}

export default function ConnectScreen({ onConnected, notice }) {
  const [type, setType] = useState(initialType);
  const [form, setForm] = useState(() => loadSaved(initialType()));
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const meta = DB_TYPES[type];
  const set = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const switchType = (t) => {
    if (t === type || busy) return;
    setType(t);
    setForm(loadSaved(t));
    setError(null);
  };

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const payload = { ...form, dbType: type, password };
      const result = await connect(payload);
      save(type, form);
      setPassword('');
      onConnected({ id: result.id, info: result.info });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const dbLabel = type === 'oracle' && form.oracleMode === 'sid' ? 'SID' : meta.dbLabel;

  return (
    <section className="connect-wrap">
      <form className="card connect-card" onSubmit={submit}>
        <div className="connect-head">
          <div className="connect-icon">
            <PlugIcon size={22} />
          </div>
          <div>
            <h2>Connect to a database</h2>
            <p className="muted">Enter your server details to see all of its tables.</p>
          </div>
        </div>

        {notice && (
          <div className="notice info">
            <InfoIcon size={18} />
            <p>{notice}</p>
          </div>
        )}

        <div className="field-label">Database type</div>
        <div className="db-types" role="radiogroup" aria-label="Database type">
          {Object.entries(DB_TYPES).map(([key, t]) => (
            <button
              type="button"
              key={key}
              role="radio"
              aria-checked={type === key}
              className={`db-type db-${key}${type === key ? ' active' : ''}`}
              onClick={() => switchType(key)}
              disabled={busy}
            >
              <span className="db-type-icon">
                <DatabaseIcon size={20} />
              </span>
              <span className="db-type-name">{t.label}</span>
              <span className="db-type-port">:{t.port}</span>
            </button>
          ))}
        </div>

        <div className="form-grid">
          <label className="field span-2">
            <span>Host</span>
            <input
              value={form.host}
              onChange={set('host')}
              placeholder="localhost or 192.168.1.10"
              required
              autoComplete="off"
              spellCheck={false}
            />
          </label>
          <label className="field">
            <span>Port</span>
            <input
              value={form.port}
              onChange={set('port')}
              placeholder={String(meta.port)}
              inputMode="numeric"
              pattern="[0-9]{1,5}"
            />
          </label>

          <label className="field span-2">
            <span>{dbLabel}</span>
            <input
              value={form.database}
              onChange={set('database')}
              placeholder={meta.dbPlaceholder}
              required
              autoComplete="off"
              spellCheck={false}
            />
          </label>
          {type === 'oracle' ? (
            <label className="field">
              <span>Connect by</span>
              <select value={form.oracleMode} onChange={set('oracleMode')}>
                <option value="service">Service name</option>
                <option value="sid">SID</option>
              </select>
            </label>
          ) : (
            <label className="field">
              <span>Schema <em>(optional)</em></span>
              <input
                value={form.schema}
                onChange={set('schema')}
                placeholder={type === 'mysql' ? '—' : 'public'}
                disabled={type === 'mysql'}
                autoComplete="off"
                spellCheck={false}
              />
            </label>
          )}

          <label className="field span-3-half">
            <span>Username</span>
            <input
              value={form.username}
              onChange={set('username')}
              required
              autoComplete="username"
              spellCheck={false}
            />
          </label>
          <label className="field span-3-half">
            <span>Password</span>
            <div className="password">
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
              <button
                type="button"
                className="eye"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                title={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOffIcon size={16} /> : <EyeIcon size={16} />}
              </button>
            </div>
          </label>

          {type === 'oracle' && (
            <label className="field span-full">
              <span>Schema <em>(optional)</em></span>
              <input
                value={form.schema}
                onChange={set('schema')}
                placeholder={meta.schemaHint}
                autoComplete="off"
                spellCheck={false}
              />
            </label>
          )}
        </div>

        {error && (
          <div className="notice error">
            <AlertIcon size={18} />
            <p>{error}</p>
          </div>
        )}

        <button type="submit" className="connect-btn" disabled={busy}>
          {busy ? (
            <>
              <span className="spinner" /> Connecting…
            </>
          ) : (
            <>
              <PlugIcon size={18} /> Connect to {meta.label}
            </>
          )}
        </button>

        <p className="connect-foot">
          <LockIcon size={13} /> The password is used only for this session and is never saved.
        </p>
      </form>
    </section>
  );
}
