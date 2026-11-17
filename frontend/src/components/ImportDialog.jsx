import { useEffect, useMemo, useRef, useState } from 'react';
import { uploadFile, importUpload, discardUpload, fetchDetails, SessionExpiredError } from '../api.js';
import {
  UploadIcon,
  XIcon,
  AlertIcon,
  CheckIcon,
  ArrowRightIcon,
  PlusTableIcon,
  ReplaceIcon,
  RowsPlusIcon,
  FileCsvIcon,
  FileExcelIcon,
  InfoIcon,
} from '../icons.jsx';

const TYPES = ['TEXT', 'INTEGER', 'DECIMAL', 'DATE', 'TIMESTAMP', 'BOOLEAN'];
const TYPE_LABELS = {
  TEXT: 'Text',
  INTEGER: 'Whole number',
  DECIMAL: 'Decimal',
  DATE: 'Date',
  TIMESTAMP: 'Date & time',
  BOOLEAN: 'True / False',
};
const ACCEPT = '.csv,.txt,.tsv,.xlsx,.xls';

const norm = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');
const keyOf = (t) => `${t.schema ?? t.catalog ?? ''}.${t.name}`;
const isExcel = (name) => /\.xlsx?$/i.test(name || '');

/**
 * Upload a CSV / Excel file into the connected database.
 * Steps: pick file -> (upload + preview) -> choose target & columns -> import -> done.
 */
export default function ImportDialog({ connectionId, tables, initialTable, onClose, onImported, onSessionExpired }) {
  const [step, setStep] = useState('pick'); // pick | uploading | configure | importing | done
  const [progress, setProgress] = useState(0);
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState(null);
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState(null);

  const [mode, setMode] = useState(initialTable ? 'append' : 'create');
  const [targetKey, setTargetKey] = useState(initialTable ? keyOf(initialTable) : tables[0] ? keyOf(tables[0]) : '');
  const [targetColumns, setTargetColumns] = useState(null); // columns of the existing target table
  const [mapping, setMapping] = useState([]); // per file column: target column name or ''
  const [newTableName, setNewTableName] = useState('');
  const [newColumns, setNewColumns] = useState([]); // per file column: { include, name, type }
  const [confirmReplace, setConfirmReplace] = useState(false);

  const uploadRef = useRef(null);
  const fileInput = useRef(null);
  const uploadIdRef = useRef(null);

  const target = tables.find((t) => keyOf(t) === targetKey) || null;
  const existingMode = mode === 'append' || mode === 'replace';

  const fail = (e) => {
    if (e instanceof SessionExpiredError) onSessionExpired(e.message);
    else setError(e.message);
  };

  // Throw the uploaded file away if the dialog closes without importing
  useEffect(
    () => () => {
      uploadRef.current?.abort();
      if (uploadIdRef.current) discardUpload(connectionId, uploadIdRef.current);
    },
    [connectionId]
  );

  // Close on Escape (not while working)
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && step !== 'importing' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [step, onClose]);

  // Columns of the chosen existing table, auto-matched to file columns by name
  useEffect(() => {
    if (!preview || !existingMode || !target) {
      setTargetColumns(null);
      return;
    }
    let cancelled = false;
    setTargetColumns(null);
    fetchDetails(connectionId, target)
      .then((d) => {
        if (cancelled) return;
        setTargetColumns(d.columns);
        setMapping(
          preview.columns.map((fc) => {
            const hit =
              d.columns.find((tc) => norm(tc.name) === norm(fc.name)) ||
              d.columns.find((tc) => norm(tc.name) === norm(fc.suggestedName));
            return hit ? hit.name : '';
          })
        );
      })
      .catch((e) => !cancelled && fail(e));
    return () => {
      cancelled = true;
    };
  }, [preview, existingMode, targetKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const startUpload = (file) => {
    if (!file) return;
    setError(null);
    setProgress(0);
    setStep('uploading');
    const up = uploadFile(connectionId, file, setProgress);
    uploadRef.current = up;
    up.promise
      .then((p) => {
        uploadIdRef.current = p.uploadId;
        setPreview(p);
        setNewTableName(p.suggestedTableName);
        setNewColumns(p.columns.map((c) => ({ include: true, name: c.suggestedName, type: c.inferredType })));
        setStep('configure');
      })
      .catch((e) => {
        if (e.name !== 'AbortError') fail(e);
        setStep('pick');
      })
      .finally(() => {
        uploadRef.current = null;
      });
  };

  const chooseAnother = () => {
    if (uploadIdRef.current) discardUpload(connectionId, uploadIdRef.current);
    uploadIdRef.current = null;
    setPreview(null);
    setError(null);
    setStep('pick');
  };

  const mappedCount = mapping.filter(Boolean).length;
  const includedCount = newColumns.filter((c) => c.include).length;
  const duplicateTargets = useMemo(() => {
    const seen = new Set();
    const dup = new Set();
    mapping.filter(Boolean).forEach((m) => (seen.has(m) ? dup.add(m) : seen.add(m)));
    return dup;
  }, [mapping]);

  const canImport =
    step === 'configure' &&
    (existingMode
      ? target && targetColumns && mappedCount > 0 && duplicateTargets.size === 0 && (mode !== 'replace' || confirmReplace)
      : newTableName.trim() && includedCount > 0);

  const runImport = async () => {
    if (!canImport) return;
    setError(null);
    setStep('importing');
    const request = existingMode
      ? {
          mode,
          schema: target.schema ?? target.catalog,
          table: target.name,
          mappings: mapping.map((t, i) => ({ source: i, target: t })).filter((m) => m.target),
        }
      : {
          mode: 'create',
          table: newTableName.trim(),
          columns: newColumns
            .map((c, i) => ({ source: i, name: c.name, type: c.type, include: c.include }))
            .filter((c) => c.include)
            .map(({ include, ...c }) => c), // eslint-disable-line no-unused-vars
        };
    try {
      const r = await importUpload(connectionId, preview.uploadId, request);
      uploadIdRef.current = null; // server already removed it
      setResult(r);
      setStep('done');
      onImported(r);
    } catch (e) {
      fail(e);
      setStep('configure');
    }
  };

  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    startUpload(e.dataTransfer.files?.[0]);
  };

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && step !== 'importing' && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="import-title">
        <div className="modal-head">
          <div className="modal-title">
            <span className="modal-icon">
              <UploadIcon size={20} />
            </span>
            <div>
              <h2 id="import-title">Upload data</h2>
              <p className="muted">
                {initialTable ? (
                  <>
                    Into <strong>{initialTable.name}</strong> or a new table
                  </>
                ) : (
                  'CSV or Excel file into a new or existing table'
                )}
              </p>
            </div>
          </div>
          <button className="icon-btn" onClick={onClose} disabled={step === 'importing'} aria-label="Close">
            <XIcon size={16} />
          </button>
        </div>

        <div className="modal-body">
          {/* ---------- Step 1: choose file ---------- */}
          {(step === 'pick' || step === 'uploading') && (
            <div
              className={`dropzone${dragging ? ' dragging' : ''}${step === 'uploading' ? ' busy' : ''}`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              onClick={() => step === 'pick' && fileInput.current?.click()}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && step === 'pick' && fileInput.current?.click()}
            >
              <input
                ref={fileInput}
                type="file"
                accept={ACCEPT}
                hidden
                onChange={(e) => startUpload(e.target.files?.[0])}
              />
              <div className="dz-icon">
                <UploadIcon size={30} />
              </div>
              {step === 'uploading' ? (
                <>
                  <h3>{progress < 1 ? 'Uploading…' : 'Reading file…'}</h3>
                  <div className="progress-bar determinate">
                    <span style={{ width: `${Math.round(progress * 100)}%` }} />
                  </div>
                  <button
                    className="text-btn"
                    onClick={(e) => {
                      e.stopPropagation();
                      uploadRef.current?.abort();
                    }}
                  >
                    Cancel
                  </button>
                </>
              ) : (
                <>
                  <h3>Drop a file here or click to browse</h3>
                  <p className="muted">CSV, TXT, TSV, XLSX or XLS · first row = column names · max 200 MB</p>
                </>
              )}
            </div>
          )}

          {error && (
            <div className="notice error">
              <AlertIcon size={18} />
              <p>{error}</p>
            </div>
          )}

          {/* ---------- Step 2: configure ---------- */}
          {preview && (step === 'configure' || step === 'importing') && (
            <>
              <div className="file-chip">
                <span className={`file-chip-icon ${isExcel(preview.fileName) ? 'xlsx' : 'csv'}`}>
                  {isExcel(preview.fileName) ? <FileExcelIcon size={18} /> : <FileCsvIcon size={18} />}
                </span>
                <div className="file-chip-text">
                  <strong>{preview.fileName}</strong>
                  <span className="muted">
                    {preview.totalRows.toLocaleString()} rows · {preview.columns.length} columns
                  </span>
                </div>
                <button className="text-btn neutral" onClick={chooseAnother} disabled={step === 'importing'}>
                  Change file
                </button>
              </div>

              <div className="field-label">What should happen?</div>
              <div className="mode-cards">
                <ModeCard
                  icon={RowsPlusIcon}
                  active={mode === 'append'}
                  disabled={!tables.length || step === 'importing'}
                  title="Add rows"
                  hint="Append to an existing table"
                  onClick={() => setMode('append')}
                />
                <ModeCard
                  icon={ReplaceIcon}
                  active={mode === 'replace'}
                  danger
                  disabled={!tables.length || step === 'importing'}
                  title="Replace data"
                  hint="Delete existing rows, then insert"
                  onClick={() => {
                    setMode('replace');
                    setConfirmReplace(false);
                  }}
                />
                <ModeCard
                  icon={PlusTableIcon}
                  active={mode === 'create'}
                  disabled={step === 'importing'}
                  title="New table"
                  hint="Create a table from this file"
                  onClick={() => setMode('create')}
                />
              </div>

              {existingMode ? (
                <>
                  <label className="field wide">
                    <span>Target table</span>
                    <select value={targetKey} onChange={(e) => setTargetKey(e.target.value)} disabled={step === 'importing'}>
                      {tables.map((t) => (
                        <option key={keyOf(t)} value={keyOf(t)}>
                          {t.name}
                          {t.schema ? `  (${t.schema})` : ''}
                        </option>
                      ))}
                    </select>
                  </label>

                  {mode === 'replace' && (
                    <label className="danger-confirm">
                      <input
                        type="checkbox"
                        checked={confirmReplace}
                        onChange={(e) => setConfirmReplace(e.target.checked)}
                      />
                      <span>
                        I understand that <strong>all existing rows in {target?.name}</strong> will be deleted
                        and replaced by the {preview.totalRows.toLocaleString()} rows from this file.
                      </span>
                    </label>
                  )}

                  <div className="section-row">
                    <h4>Match columns</h4>
                    <span className="muted small">
                      {mappedCount} of {preview.columns.length} file columns matched · unmatched columns are skipped
                    </span>
                  </div>
                  {!targetColumns ? (
                    <p className="muted">Loading table columns…</p>
                  ) : (
                    <div className="map-table-wrap">
                      <table className="map-table">
                        <thead>
                          <tr>
                            <th>File column</th>
                            <th>Example</th>
                            <th aria-label="goes to" />
                            <th>Table column</th>
                          </tr>
                        </thead>
                        <tbody>
                          {preview.columns.map((fc, i) => (
                            <tr key={fc.index} className={mapping[i] ? '' : 'skipped'}>
                              <td className="mono">{fc.name}</td>
                              <td className="muted example">{preview.sampleRows[0]?.[i] ?? ''}</td>
                              <td className="arrow">
                                <ArrowRightIcon size={14} />
                              </td>
                              <td>
                                <select
                                  className={duplicateTargets.has(mapping[i]) ? 'invalid' : ''}
                                  value={mapping[i] || ''}
                                  disabled={step === 'importing'}
                                  onChange={(e) =>
                                    setMapping((m) => m.map((v, j) => (j === i ? e.target.value : v)))
                                  }
                                >
                                  <option value="">— skip —</option>
                                  {targetColumns.map((tc) => (
                                    <option key={tc.name} value={tc.name}>
                                      {tc.name} · {tc.type}
                                    </option>
                                  ))}
                                </select>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  {duplicateTargets.size > 0 && (
                    <p className="inline-error">Each table column can be used only once: {[...duplicateTargets].join(', ')}</p>
                  )}
                </>
              ) : (
                <>
                  <label className="field wide">
                    <span>New table name</span>
                    <input
                      value={newTableName}
                      onChange={(e) => setNewTableName(e.target.value)}
                      disabled={step === 'importing'}
                      spellCheck={false}
                    />
                  </label>

                  <div className="section-row">
                    <h4>Columns</h4>
                    <span className="muted small">Types were detected from the data – change them if needed</span>
                  </div>
                  <div className="map-table-wrap">
                    <table className="map-table">
                      <thead>
                        <tr>
                          <th aria-label="include" />
                          <th>File column</th>
                          <th>Example</th>
                          <th>Column name</th>
                          <th>Type</th>
                        </tr>
                      </thead>
                      <tbody>
                        {preview.columns.map((fc, i) => {
                          const nc = newColumns[i];
                          const update = (patch) =>
                            setNewColumns((cols) => cols.map((c, j) => (j === i ? { ...c, ...patch } : c)));
                          return (
                            <tr key={fc.index} className={nc.include ? '' : 'skipped'}>
                              <td>
                                <input
                                  type="checkbox"
                                  checked={nc.include}
                                  onChange={(e) => update({ include: e.target.checked })}
                                  disabled={step === 'importing'}
                                  aria-label={`Include ${fc.name}`}
                                />
                              </td>
                              <td className="mono">{fc.name}</td>
                              <td className="muted example">{preview.sampleRows[0]?.[i] ?? ''}</td>
                              <td>
                                <input
                                  className="cell-input"
                                  value={nc.name}
                                  onChange={(e) => update({ name: e.target.value })}
                                  disabled={!nc.include || step === 'importing'}
                                  spellCheck={false}
                                />
                              </td>
                              <td>
                                <select
                                  value={nc.type}
                                  onChange={(e) => update({ type: e.target.value })}
                                  disabled={!nc.include || step === 'importing'}
                                >
                                  {TYPES.map((t) => (
                                    <option key={t} value={t}>
                                      {TYPE_LABELS[t]}
                                    </option>
                                  ))}
                                </select>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </>
              )}

              <details className="data-preview">
                <summary>Preview first {preview.sampleRows.length} rows of the file</summary>
                <div className="map-table-wrap">
                  <table className="map-table compact">
                    <thead>
                      <tr>
                        {preview.columns.map((c) => (
                          <th key={c.index}>{c.name}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {preview.sampleRows.map((r, i) => (
                        <tr key={i}>
                          {r.map((v, j) => (
                            <td key={j}>{v}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>

              <p className="hint">
                <InfoIcon size={14} /> Empty cells are stored as NULL. Dates may be 2026-05-31, 31-05-2026 or 31/05/2026.
                If any row fails, nothing is imported.
              </p>
            </>
          )}

          {/* ---------- Step 3: done ---------- */}
          {step === 'done' && result && (
            <div className="done">
              <div className="done-icon">
                <CheckIcon size={30} />
              </div>
              <h3>Import complete</h3>
              <p>
                <strong>{result.rowsInserted.toLocaleString()}</strong> rows{' '}
                {result.created ? 'loaded into the new table' : 'added to'} <strong>{result.table.name}</strong>
                {result.rowsDeleted > 0 && <> ({result.rowsDeleted.toLocaleString()} old rows deleted)</>} in{' '}
                {(result.millis / 1000).toFixed(1)} s.
              </p>
            </div>
          )}
        </div>

        <div className="modal-foot">
          {step === 'done' ? (
            <button className="btn primary" onClick={onClose}>
              Done
            </button>
          ) : (
            <>
              <button className="btn" onClick={onClose} disabled={step === 'importing'}>
                Cancel
              </button>
              {(step === 'configure' || step === 'importing') && (
                <button
                  className={`btn primary${mode === 'replace' ? ' danger' : ''}`}
                  onClick={runImport}
                  disabled={!canImport}
                >
                  {step === 'importing' ? (
                    <>
                      <span className="spinner" /> Importing…
                    </>
                  ) : mode === 'create' ? (
                    `Create table & import ${preview?.totalRows.toLocaleString()} rows`
                  ) : mode === 'replace' ? (
                    `Replace with ${preview?.totalRows.toLocaleString()} rows`
                  ) : (
                    `Import ${preview?.totalRows.toLocaleString()} rows`
                  )}
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function ModeCard({ icon: Icon, active, disabled, title, hint, onClick, danger }) {
  return (
    <button
      type="button"
      className={`mode-card${active ? ' active' : ''}${danger ? ' danger' : ''}`}
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
    >
      <span className="mode-icon">
        <Icon size={18} />
      </span>
      <span className="mode-text">
        <span className="mode-title">{title}</span>
        <span className="mode-hint">{hint}</span>
      </span>
    </button>
  );
}
