import {
  TableIcon,
  ColumnsIcon,
  RowsIcon,
  FolderIcon,
  FileCsvIcon,
  FileExcelIcon,
  DownloadIcon,
  CheckIcon,
  AlertIcon,
  InfoIcon,
  XIcon,
  UploadIcon,
} from '../icons.jsx';

const NOTICE_ICONS = { success: CheckIcon, error: AlertIcon, info: InfoIcon };

export default function TableDetails({
  table,
  details,
  loading,
  download,
  message,
  onDismiss,
  onDownload,
  onCancel,
  onUpload,
  formatBytes,
}) {
  if (!table) {
    return (
      <section className="card detail-card empty-state">
        <div className="empty-art">
          <TableIcon size={40} />
        </div>
        <h3>Select a table</h3>
        <p>Pick a table on the left to see its columns and download all of its data.</p>
      </section>
    );
  }

  const rowCount = details?.rowCount;
  const NoticeIcon = message ? NOTICE_ICONS[message.type] : null;

  return (
    <section className="card detail-card">
      <div className="detail-head">
        <div className="detail-title">
          <span className="detail-icon">
            <TableIcon size={20} />
          </span>
          <div>
            <h2>{table.name}</h2>
            <span className="muted">{table.schema || table.catalog}</span>
          </div>
        </div>
      </div>

      <div className="stats">
        <Stat icon={ColumnsIcon} label="Columns" value={details ? details.columns.length : null} />
        <Stat
          icon={RowsIcon}
          label="Rows"
          value={details ? (rowCount >= 0 ? rowCount.toLocaleString() : '?') : null}
        />
        <Stat icon={FolderIcon} label="Schema" value={table.schema || table.catalog || '-'} />
      </div>

      <div className="downloads">
        <DownloadOption
          kind="csv"
          icon={FileCsvIcon}
          title="CSV"
          hint="Plain text, any size, opens anywhere"
          busy={download?.format === 'csv'}
          disabled={!!download}
          onClick={() => onDownload('csv')}
        />
        <DownloadOption
          kind="xlsx"
          icon={FileExcelIcon}
          title="Excel"
          hint="Formatted .xlsx with typed columns"
          busy={download?.format === 'xlsx'}
          disabled={!!download}
          onClick={() => onDownload('xlsx')}
        />
        <button className="dl-option dl-upload" onClick={onUpload} disabled={!!download}>
          <span className="dl-icon">
            <UploadIcon size={22} />
          </span>
          <span className="dl-text">
            <span className="dl-title">Upload data</span>
            <span className="dl-hint">Add or replace rows from a CSV / Excel file</span>
          </span>
          <span className="dl-arrow">
            <UploadIcon size={18} />
          </span>
        </button>
      </div>

      {download && (
        <div className="progress-box">
          <div className="progress-row">
            <span>
              Fetching all rows as <strong>{download.format === 'xlsx' ? 'Excel' : 'CSV'}</strong>…{' '}
              <span className="mono">{formatBytes(download.bytes)}</span>
            </span>
            <button className="text-btn" onClick={onCancel}>
              Cancel
            </button>
          </div>
          <div className="progress-bar">
            <span />
          </div>
        </div>
      )}

      {message && (
        <div className={`notice ${message.type}`}>
          <NoticeIcon size={18} />
          <p>{message.text}</p>
          <button className="notice-close" onClick={onDismiss} aria-label="Dismiss">
            <XIcon size={14} />
          </button>
        </div>
      )}

      <div className="columns-section">
        <h3>Columns</h3>
        <div className="columns-wrap">
          <table className="columns">
            <thead>
              <tr>
                <th>#</th>
                <th>Name</th>
                <th>Type</th>
                <th>Size</th>
                <th>Nullable</th>
              </tr>
            </thead>
            <tbody>
              {loading &&
                Array.from({ length: 4 }).map((_, i) => (
                  <tr key={i}>
                    {Array.from({ length: 5 }).map((__, j) => (
                      <td key={j}>
                        <span className="skeleton sk-cell" />
                      </td>
                    ))}
                  </tr>
                ))}
              {details?.columns.map((c, i) => (
                <tr key={c.name}>
                  <td className="muted">{i + 1}</td>
                  <td className="mono col-name">{c.name}</td>
                  <td>
                    <span className="type-chip">{c.type}</span>
                  </td>
                  <td className="muted">{c.size > 0 && c.size < 2147483647 ? c.size : '—'}</td>
                  <td>
                    <span className={`null-chip ${c.nullable ? 'yes' : 'no'}`}>
                      {c.nullable ? 'NULL' : 'NOT NULL'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}

function Stat({ icon: Icon, label, value }) {
  return (
    <div className="stat">
      <span className="stat-icon">
        <Icon size={18} />
      </span>
      <div>
        <div className="stat-label">{label}</div>
        <div className="stat-value">
          {value === null ? <span className="skeleton sk-stat" /> : value}
        </div>
      </div>
    </div>
  );
}

function DownloadOption({ kind, icon: Icon, title, hint, busy, disabled, onClick }) {
  return (
    <button className={`dl-option dl-${kind}${busy ? ' busy' : ''}`} onClick={onClick} disabled={disabled}>
      <span className="dl-icon">
        <Icon size={22} />
      </span>
      <span className="dl-text">
        <span className="dl-title">Download {title}</span>
        <span className="dl-hint">{hint}</span>
      </span>
      <span className="dl-arrow">{busy ? <span className="spinner" /> : <DownloadIcon size={18} />}</span>
    </button>
  );
}
