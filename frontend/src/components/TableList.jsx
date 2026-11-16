import { SearchIcon, RefreshIcon, TableIcon, AlertIcon } from '../icons.jsx';

export default function TableList({
  tables,
  total,
  loading,
  error,
  filter,
  onFilter,
  onRefresh,
  selectedKey,
  keyOf,
  onSelect,
  disabled,
}) {
  return (
    <section className="card list-card">
      <div className="card-head">
        <div className="card-title">
          <h2>Tables</h2>
          {!loading && !error && <span className="badge">{total}</span>}
        </div>
        <button
          className="icon-btn"
          onClick={onRefresh}
          disabled={loading || disabled}
          title="Refresh table list"
          aria-label="Refresh table list"
        >
          <RefreshIcon size={16} className={loading ? 'spin' : ''} />
        </button>
      </div>

      <label className="search">
        <SearchIcon size={16} />
        <input
          type="search"
          placeholder="Search tables…"
          value={filter}
          onChange={(e) => onFilter(e.target.value)}
        />
      </label>

      <div className="list-scroll">
        {loading && (
          <ul className="table-list">
            {Array.from({ length: 6 }).map((_, i) => (
              <li key={i} className="skeleton-row">
                <span className="skeleton sk-icon" />
                <span className="skeleton sk-text" style={{ width: `${45 + ((i * 17) % 40)}%` }} />
              </li>
            ))}
          </ul>
        )}

        {error && (
          <div className="notice error">
            <AlertIcon size={18} />
            <div>
              <strong>Could not load tables</strong>
              <p>{error}</p>
            </div>
          </div>
        )}

        {!loading && !error && tables.length === 0 && (
          <p className="empty-text">
            {total === 0 ? 'No tables found in this schema.' : 'No table matches your search.'}
          </p>
        )}

        {!loading && !error && (
          <ul className="table-list" role="listbox" aria-label="Tables">
            {tables.map((t) => {
              const key = keyOf(t);
              const active = key === selectedKey;
              return (
                <li key={key}>
                  <button
                    role="option"
                    aria-selected={active}
                    className={`table-item${active ? ' active' : ''}`}
                    onClick={() => onSelect(t)}
                    disabled={disabled && !active}
                    title={key}
                  >
                    <span className="ti-icon">
                      <TableIcon size={15} />
                    </span>
                    <span className="table-name">{t.name}</span>
                    {(t.schema || t.catalog) && (
                      <span className="table-schema">{t.schema || t.catalog}</span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </section>
  );
}
