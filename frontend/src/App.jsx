import { useEffect, useState } from 'react';
import { disconnect, fetchConnection, SessionExpiredError } from './api.js';
import ConnectScreen from './components/ConnectScreen.jsx';
import Explorer from './components/Explorer.jsx';
import ThemeToggle from './components/ThemeToggle.jsx';
import { DatabaseIcon, LogoutIcon } from './icons.jsx';

const SESSION_KEY = 'tableapp.session';

function useTheme() {
  const [theme, setTheme] = useState(
    () => document.documentElement.getAttribute('data-theme') || 'light'
  );
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('theme', theme);
    } catch {
      /* storage blocked – theme just won't be remembered */
    }
  }, [theme]);
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))];
}

/** The open connection survives a page refresh (this browser tab only). */
function loadSession() {
  try {
    return JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null');
  } catch {
    return null;
  }
}

function storeSession(s) {
  try {
    if (s) sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
    /* ignore */
  }
}

export default function App() {
  const [theme, toggleTheme] = useTheme();
  const [connection, setConnection] = useState(loadSession); // { id, info }
  const [notice, setNotice] = useState(null);

  // After a refresh, make sure the saved session still exists on the server
  useEffect(() => {
    if (!connection) return;
    fetchConnection(connection.id)
      .then((info) => setConnection((c) => (c ? { ...c, info } : c)))
      .catch((e) => {
        if (e instanceof SessionExpiredError) endSession(e.message);
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => storeSession(connection), [connection]);

  const endSession = (message) => {
    setConnection(null);
    setNotice(message || null);
  };

  const handleDisconnect = async () => {
    if (connection) await disconnect(connection.id);
    endSession(null);
  };

  const info = connection?.info;

  return (
    <div className="app">
      <header className="hero">
        <div className="hero-inner">
          <div className="brand">
            <div className="brand-logo">
              <DatabaseIcon size={22} />
            </div>
            <div>
              <h1>Table Export</h1>
              <p>Connect to PostgreSQL, Oracle or MySQL and download any table as CSV or Excel.</p>
            </div>
          </div>
          <div className="hero-actions">
            {info && (
              <div
                className="conn-pill"
                title={`${info.product} ${info.version}\nUser: ${info.user}\nSchema: ${info.schema}`}
              >
                <span className="conn-dot" />
                <span className="conn-db">{info.product}</span>
                <span className="conn-sep">/</span>
                <span>{info.database}</span>
                {info.schema && info.schema !== info.database && (
                  <>
                    <span className="conn-sep">/</span>
                    <span>{info.schema}</span>
                  </>
                )}
              </div>
            )}
            {connection && (
              <button className="hero-btn" onClick={handleDisconnect} title="Disconnect">
                <LogoutIcon size={16} />
                <span>Disconnect</span>
              </button>
            )}
            <ThemeToggle theme={theme} onToggle={toggleTheme} />
          </div>
        </div>
      </header>

      {connection ? (
        <Explorer key={connection.id} connectionId={connection.id} onSessionExpired={endSession} />
      ) : (
        <ConnectScreen
          notice={notice}
          onConnected={(c) => {
            setNotice(null);
            setConnection(c);
          }}
        />
      )}
    </div>
  );
}
