import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchTables, fetchDetails, downloadTable, SessionExpiredError } from '../api.js';
import TableList from './TableList.jsx';
import TableDetails from './TableDetails.jsx';

const keyOf = (t) => `${t.schema ?? t.catalog ?? ''}.${t.name}`;

/** Table list + details for one open connection. */
export default function Explorer({ connectionId, onSessionExpired }) {
  const [tables, setTables] = useState([]);
  const [loadingTables, setLoadingTables] = useState(true);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('');

  const [selected, setSelected] = useState(null);
  const [details, setDetails] = useState(null);
  const [loadingDetails, setLoadingDetails] = useState(false);

  const [download, setDownload] = useState(null); // { format, bytes } while running
  const [message, setMessage] = useState(null);
  const abortRef = useRef(null);

  const handleError = (e, setter) => {
    if (e instanceof SessionExpiredError) onSessionExpired(e.message);
    else setter(e.message);
  };

  const loadTables = () => {
    setLoadingTables(true);
    setError(null);
    fetchTables(connectionId)
      .then((list) => {
        setTables(list);
        // ?table=NAME pre-selects a table (bookmarkable link)
        const wanted = new URLSearchParams(window.location.search).get('table');
        const match = wanted && list.find((t) => t.name.toLowerCase() === wanted.toLowerCase());
        if (match) setSelected((cur) => cur ?? match);
      })
      .catch((e) => handleError(e, setError))
      .finally(() => setLoadingTables(false));
  };

  useEffect(loadTables, [connectionId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selected) return;
    let cancelled = false;
    setDetails(null);
    setMessage(null);
    setLoadingDetails(true);
    fetchDetails(connectionId, selected)
      .then((d) => !cancelled && setDetails(d))
      .catch((e) => !cancelled && handleError(e, (text) => setMessage({ type: 'error', text })))
      .finally(() => !cancelled && setLoadingDetails(false));
    return () => {
      cancelled = true;
    };
  }, [selected, connectionId]); // eslint-disable-line react-hooks/exhaustive-deps

  const visibleTables = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return f ? tables.filter((t) => t.name.toLowerCase().includes(f)) : tables;
  }, [tables, filter]);

  const startDownload = async (format) => {
    if (!selected || download) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setMessage(null);
    setDownload({ format, bytes: 0 });
    try {
      const bytes = await downloadTable(
        connectionId,
        selected,
        format,
        (b) => setDownload({ format, bytes: b }),
        controller.signal
      );
      setMessage({
        type: 'success',
        text: `${selected.name} downloaded as ${format === 'xlsx' ? 'Excel' : 'CSV'} (${formatBytes(bytes)}).`,
      });
    } catch (e) {
      if (e.name === 'AbortError') {
        setMessage({ type: 'info', text: 'Download cancelled.' });
      } else {
        handleError(e, (text) => setMessage({ type: 'error', text }));
      }
    } finally {
      setDownload(null);
      abortRef.current = null;
    }
  };

  return (
    <main className="layout">
      <TableList
        tables={visibleTables}
        total={tables.length}
        loading={loadingTables}
        error={error}
        filter={filter}
        onFilter={setFilter}
        onRefresh={loadTables}
        selectedKey={selected ? keyOf(selected) : null}
        keyOf={keyOf}
        onSelect={(t) => !download && setSelected(t)}
        disabled={!!download}
      />

      <TableDetails
        table={selected}
        details={details}
        loading={loadingDetails}
        download={download}
        message={message}
        onDismiss={() => setMessage(null)}
        onDownload={startDownload}
        onCancel={() => abortRef.current?.abort()}
        formatBytes={formatBytes}
      />
    </main>
  );
}

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
