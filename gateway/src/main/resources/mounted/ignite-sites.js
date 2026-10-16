/**
 * Ignite Gateway Config Page — Sites
 *
 * SystemJS module exported as "ignite-sites".
 * Loaded by Ignition's gateway config UI when the "Connections > Ignite > Sites" nav entry is visited.
 *
 * Calls the Ignite REST API at /data/ignite/sites for full CRUD.
 * Auto-refreshes sync status every 10 seconds.
 */
System.register(['react'], function (_export) {
    'use strict';

    var React;

    return {
        setters: [function (m) {
            React = m.default || m;
        }],
        execute: function () {

            var h = React.createElement;
            var useState   = React.useState;
            var useEffect  = React.useEffect;
            var useRef     = React.useRef;
            var useCallback = React.useCallback;
            var Fragment   = React.Fragment;

            // ── Constants ────────────────────────────────────────────────────
            var API_BASE = '/data/ignite/sites';
            var SETTINGS_URL = '/data/ignite/settings';
            var POLL_MS  = 10000;

            // ── Styles ───────────────────────────────────────────────────────
            var S = {
                page: {
                    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    fontSize: '14px',
                    color: '#333',
                    padding: '20px',
                },
                header: {
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    marginBottom: '16px',
                },
                title: {
                    fontSize: '18px',
                    fontWeight: '600',
                    margin: 0,
                },
                btn: {
                    display: 'inline-flex',
                    alignItems: 'center',
                    padding: '6px 14px',
                    border: '1px solid #ccc',
                    borderRadius: '4px',
                    background: '#f5f5f5',
                    cursor: 'pointer',
                    fontSize: '13px',
                    marginLeft: '6px',
                    lineHeight: '1.4',
                },
                btnPrimary: {
                    background: '#1e6fa8',
                    color: '#fff',
                    border: '1px solid #1a5f8f',
                },
                btnDanger: {
                    background: '#c0392b',
                    color: '#fff',
                    border: '1px solid #a93226',
                },
                btnSmall: {
                    padding: '3px 8px',
                    fontSize: '12px',
                },
                table: {
                    width: '100%',
                    borderCollapse: 'collapse',
                    marginTop: '8px',
                },
                th: {
                    textAlign: 'left',
                    padding: '8px 10px',
                    background: '#f0f2f5',
                    borderBottom: '2px solid #d0d3d8',
                    fontWeight: '600',
                    whiteSpace: 'nowrap',
                },
                td: {
                    padding: '8px 10px',
                    borderBottom: '1px solid #e5e7ea',
                    verticalAlign: 'middle',
                },
                trHover: {
                    background: '#f8f9fb',
                },
                badge: {
                    display: 'inline-block',
                    padding: '2px 8px',
                    borderRadius: '10px',
                    fontSize: '11px',
                    fontWeight: '600',
                    textTransform: 'uppercase',
                },
                badgeOk:      { background: '#d4edda', color: '#155724' },
                badgeError:   { background: '#f8d7da', color: '#721c24' },
                badgePending: { background: '#fff3cd', color: '#856404' },
                badgeSyncing: { background: '#cce5ff', color: '#004085' },
                badgeOff:     { background: '#e2e3e5', color: '#383d41' },
                // Modal overlay
                overlay: {
                    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
                    background: 'rgba(0,0,0,0.4)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    zIndex: 1000,
                },
                modal: {
                    background: '#fff',
                    borderRadius: '6px',
                    boxShadow: '0 8px 32px rgba(0,0,0,0.18)',
                    width: '560px',
                    maxWidth: '95vw',
                    maxHeight: '90vh',
                    display: 'flex',
                    flexDirection: 'column',
                },
                modalHeader: {
                    padding: '16px 20px',
                    borderBottom: '1px solid #e5e7ea',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                },
                modalTitle: { fontSize: '16px', fontWeight: '600', margin: 0 },
                modalBody: { padding: '20px', overflowY: 'auto', flex: 1 },
                modalFooter: {
                    padding: '12px 20px',
                    borderTop: '1px solid #e5e7ea',
                    display: 'flex',
                    justifyContent: 'flex-end',
                    gap: '8px',
                },
                formGroup: { marginBottom: '14px' },
                label: { display: 'block', fontWeight: '500', marginBottom: '4px', fontSize: '13px' },
                input: {
                    width: '100%', boxSizing: 'border-box',
                    padding: '6px 10px', border: '1px solid #ccc', borderRadius: '4px',
                    fontSize: '13px', lineHeight: '1.5',
                },
                textarea: {
                    width: '100%', boxSizing: 'border-box',
                    padding: '6px 10px', border: '1px solid #ccc', borderRadius: '4px',
                    fontSize: '12px', fontFamily: 'monospace', resize: 'vertical',
                    lineHeight: '1.5',
                },
                hint: { fontSize: '11px', color: '#666', marginTop: '2px' },
                row2: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' },
                // Toast
                toast: {
                    position: 'fixed', bottom: '24px', right: '24px',
                    padding: '12px 18px',
                    borderRadius: '6px',
                    boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
                    fontSize: '13px',
                    zIndex: 2000,
                    maxWidth: '360px',
                },
                toastSuccess: { background: '#155724', color: '#fff' },
                toastError:   { background: '#721c24', color: '#fff' },
                toastInfo:    { background: '#004085', color: '#fff' },
                // Confirm dialog
                confirm: {
                    padding: '24px',
                    maxWidth: '380px',
                    width: '95vw',
                    textAlign: 'center',
                },
                // Empty state
                empty: {
                    textAlign: 'center', padding: '48px 24px', color: '#888',
                },
                // Secret field row
                secretRow: {
                    display: 'flex', gap: '8px', alignItems: 'center',
                },
                secretInput: {
                    flex: 1, padding: '6px 10px', border: '1px solid #ccc',
                    borderRadius: '4px', fontSize: '13px',
                },
                testResult: {
                    marginTop: '12px', padding: '10px 14px', borderRadius: '4px',
                    fontSize: '13px',
                },
                testOk:    { background: '#d4edda', color: '#155724' },
                testFail:  { background: '#f8d7da', color: '#721c24' },
            };

            // ── Utilities ────────────────────────────────────────────────────

            var csrfTokenPromise = null;

            function getCsrfToken() {
                if (!csrfTokenPromise) {
                    csrfTokenPromise = fetch('/data/app/session', {
                        headers: { Accept: 'application/json' },
                        credentials: 'same-origin',
                    })
                        .then(function (res) {
                            if (!res.ok) { throw new Error('HTTP ' + res.status); }
                            return res.json();
                        })
                        .then(function (json) { return (json && json.csrfToken) || null; })
                        .catch(function (e) {
                            console.warn('Ignite: could not fetch CSRF token', e);
                            return null;
                        });
                }
                return csrfTokenPromise;
            }

            function apiFetch(url, options) {
                var opts = options || {};
                var method = (opts.method || 'GET').toUpperCase();
                var unsafe = method !== 'GET' && method !== 'HEAD';
                var headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
                var ready = unsafe
                    ? getCsrfToken().then(function (token) {
                        if (token) { headers['X-CSRF-Token'] = token; }
                    })
                    : Promise.resolve();
                return ready
                    .then(function () {
                        return fetch(url, Object.assign({}, opts, { headers: headers }));
                    })
                    .then(function (res) {
                        if (!res.ok) {
                            if (unsafe && res.status === 403) { csrfTokenPromise = null; }
                            return res.text().then(function (body) {
                                throw new Error('HTTP ' + res.status + ': ' + body);
                            });
                        }
                        var ct = res.headers.get('content-type') || '';
                        if (ct.indexOf('application/json') >= 0 || ct.indexOf('text/json') >= 0) {
                            return res.json();
                        }
                        return res.text();
                    });
            }

            function syncBadge(sync) {
                if (!sync) return h('span', { style: Object.assign({}, S.badge, S.badgeOff) }, 'N/A');
                var state = (sync.state || '').toUpperCase();
                var styleMap = { OK: S.badgeOk, ERROR: S.badgeError, SYNCING: S.badgeSyncing };
                var style = styleMap[state] || S.badgePending;
                return h('span', { style: Object.assign({}, S.badge, style) }, state || 'PENDING');
            }

            function formatTime(ts) {
                if (!ts) return '—';
                try {
                    return new Date(ts).toLocaleString();
                } catch (_) { return ts; }
            }

            function genApiKey() {
                var arr = new Uint8Array(24);
                crypto.getRandomValues(arr);
                return Array.from(arr).map(function (b) {
                    return b.toString(16).padStart(2, '0');
                }).join('');
            }

            // ── BLANK FORM ───────────────────────────────────────────────────
            var BLANK = {
                name: '', mountPath: '', repoUrl: '', branch: 'main',
                pat: '', pollIntervalMinutes: 5,
                envVars: '', corsOrigins: '', apiKey: '', servePath: '/',
                enabled: true, requireSignature: false,
                writeSecurityLevel: '', adminSecurityLevel: '', auditProfileName: '',
                tagPathPatterns: '',
            };

            // ── Toast component ──────────────────────────────────────────────
            function Toast(props) {
                var msg = props.message;
                var kind = props.kind; // 'success' | 'error' | 'info'
                var styleMap = { success: S.toastSuccess, error: S.toastError, info: S.toastInfo };
                return h('div', {
                    style: Object.assign({}, S.toast, styleMap[kind] || S.toastInfo),
                }, msg);
            }

            // ── Confirm dialog ───────────────────────────────────────────────
            function ConfirmDialog(props) {
                if (!props.open) return null;
                return h('div', { style: S.overlay, onClick: props.onCancel },
                    h('div', {
                        style: Object.assign({}, S.modal, { width: 'auto' }),
                        onClick: function (e) { e.stopPropagation(); }
                    },
                        h('div', { style: S.confirm },
                            h('p', { style: { fontSize: '15px', marginBottom: '20px' } }, props.message),
                            h('div', { style: { display: 'flex', gap: '10px', justifyContent: 'center' } },
                                h('button', {
                                    style: Object.assign({}, S.btn, S.btnDanger),
                                    onClick: props.onConfirm,
                                }, 'Delete'),
                                h('button', { style: S.btn, onClick: props.onCancel }, 'Cancel')
                            )
                        )
                    )
                );
            }

            // ── Site Form Modal ──────────────────────────────────────────────
            function SiteForm(props) {
                var site = props.site; // null = add, object = edit
                var onClose = props.onClose;
                var onSave = props.onSave;

                var isEdit = site !== null;

                // Form state — populate from site or blank
                var _s0 = useState(isEdit ? {
                    name: site.name || '',
                    mountPath: site.mountPath || '',
                    repoUrl: site.repoUrl || '',
                    branch: site.branch || 'main',
                    pat: '',               // never prefill secrets
                    pollIntervalMinutes: site.pollIntervalMinutes != null ? site.pollIntervalMinutes : 5,
                    envVars: site.envVars || '',
                    corsOrigins: site.corsOrigins || '',
                    apiKey: '',            // never prefill secrets
                    servePath: site.servePath || '/',
                    enabled: site.enabled !== false,
                    requireSignature: site.requireSignature === true,
                    writeSecurityLevel: site.writeSecurityLevel || '',
                    adminSecurityLevel: site.adminSecurityLevel || '',
                    auditProfileName: site.auditProfileName || '',
                    tagPathPatterns: site.tagPathPatterns || '',
                } : Object.assign({}, BLANK));
                var form = _s0[0];
                var setForm = _s0[1];

                var _s1 = useState(false);
                var saving = _s1[0];
                var setSaving = _s1[1];

                var _s2 = useState(null);
                var error = _s2[0];
                var setError = _s2[1];

                var _s3 = useState(null);
                var testResult = _s3[0];
                var setTestResult = _s3[1];

                var _s4 = useState(false);
                var testing = _s4[0];
                var setTesting = _s4[1];

                function field(key) {
                    return {
                        value: form[key],
                        onChange: function (e) {
                            var val = e.target.type === 'checkbox' ? e.target.checked
                                : e.target.type === 'number' ? parseInt(e.target.value, 10) || 0
                                : e.target.value;
                            setForm(function (prev) {
                                var next = Object.assign({}, prev);
                                next[key] = val;
                                return next;
                            });
                        },
                    };
                }

                function handleSave(e) {
                    e.preventDefault();
                    setError(null);
                    setSaving(true);

                    // Build payload — omit empty secret fields (keep existing server value)
                    var payload = Object.assign({}, form);
                    if (!payload.pat)    delete payload.pat;
                    if (!payload.apiKey) delete payload.apiKey;

                    var url = isEdit ? API_BASE + '/' + site.id : API_BASE;
                    var method = isEdit ? 'PUT' : 'POST';

                    apiFetch(url, { method: method, body: JSON.stringify(payload) })
                        .then(function (saved) {
                            setSaving(false);
                            onSave(saved);
                        })
                        .catch(function (err) {
                            setSaving(false);
                            setError(err.message || 'Save failed');
                        });
                }

                function handleTest() {
                    if (!isEdit) return;
                    setTestResult(null);
                    setTesting(true);
                    apiFetch(API_BASE + '/' + site.id + '/test', { method: 'POST' })
                        .then(function (r) {
                            setTesting(false);
                            setTestResult(r);
                        })
                        .catch(function (err) {
                            setTesting(false);
                            setTestResult({ success: false, message: err.message });
                        });
                }

                var inp = function (key, type, opts) {
                    var extra = opts || {};
                    return h('input', Object.assign({
                        type: type || 'text',
                        style: S.input,
                    }, field(key), extra));
                };

                var secretField = function (key, configured, label, showGenerate) {
                    return h('div', { style: S.formGroup },
                        h('label', { style: S.label }, label),
                        h('div', { style: S.secretRow },
                            h('input', {
                                type: 'password',
                                style: S.secretInput,
                                placeholder: configured ? '(configured — leave blank to keep)' : 'Enter value',
                                value: form[key],
                                onChange: field(key).onChange,
                                autoComplete: 'new-password',
                            }),
                            showGenerate && h('button', {
                                type: 'button',
                                style: S.btn,
                                onClick: function () {
                                    setForm(function (prev) {
                                        var next = Object.assign({}, prev);
                                        next[key] = genApiKey();
                                        return next;
                                    });
                                },
                            }, 'Generate'),
                        ),
                        configured && !form[key] && h('div', { style: S.hint }, '✓ Already configured'),
                    );
                };

                return h('div', { style: S.overlay, onClick: onClose },
                    h('div', {
                        style: S.modal,
                        onClick: function (e) { e.stopPropagation(); }
                    },
                        h('div', { style: S.modalHeader },
                            h('h2', { style: S.modalTitle }, isEdit ? 'Edit Site' : 'Add Site'),
                            h('button', {
                                style: Object.assign({}, S.btn, { marginLeft: 0 }),
                                onClick: onClose,
                            }, '✕')
                        ),
                        h('form', { onSubmit: handleSave },
                            h('div', { style: S.modalBody },
                                error && h('div', {
                                    style: Object.assign({}, S.testResult, S.testFail, { marginBottom: '12px', marginTop: 0 })
                                }, error),

                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'Name *'),
                                    inp('name', 'text', { required: true, placeholder: 'my-site' }),
                                ),
                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'Mount Path *'),
                                    inp('mountPath', 'text', { required: true, placeholder: '/my-site' }),
                                    h('div', { style: S.hint }, 'URL path prefix where the site is served'),
                                ),
                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'Repo URL *'),
                                    inp('repoUrl', 'text', { required: true, placeholder: 'https://github.com/org/repo' }),
                                ),
                                h('div', { style: S.row2 },
                                    h('div', { style: S.formGroup },
                                        h('label', { style: S.label }, 'Branch'),
                                        inp('branch', 'text', { placeholder: 'main' }),
                                    ),
                                    h('div', { style: S.formGroup },
                                        h('label', { style: S.label }, 'Poll Interval (min)'),
                                        inp('pollIntervalMinutes', 'number', { min: 1, max: 1440 }),
                                    ),
                                ),
                                secretField('pat', isEdit && site.patConfigured, 'Personal Access Token (PAT)', false),
                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'Serve Path'),
                                    inp('servePath', 'text', { placeholder: '/' }),
                                    h('div', { style: S.hint }, 'Sub-directory within the repo to serve files from'),
                                ),
                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'CORS Origins'),
                                    inp('corsOrigins', 'text', { placeholder: '* or https://example.com' }),
                                ),
                                h('div', { style: S.formGroup },
                                    h('label', { style: S.label }, 'Env Vars (JSON)'),
                                    h('textarea', Object.assign({ style: S.textarea, rows: 3,
                                        placeholder: '{"API_URL": "https://..."}' }, field('envVars'))),
                                    h('div', { style: S.hint }, 'Injected as window.__IGNITE_ENV__ in served HTML'),
                                ),
                                secretField('apiKey', isEdit && site.apiKeyConfigured, 'API Key', true),
                                h('div', { style: S.formGroup },
                                    h('label', null,
                                        h('input', Object.assign({ type: 'checkbox', style: { marginRight: '6px' } }, field('enabled'))),
                                        'Enabled',
                                    ),
                                ),
                                h('div', { style: S.formGroup },
                                    h('label', null,
                                        h('input', Object.assign({ type: 'checkbox', style: { marginRight: '6px' } }, field('requireSignature'))),
                                        'Require e-signature',
                                    ),
                                    h('div', { style: S.hint }, 'Tag writes, alarm acks and library scripts must carry a valid signature'),
                                ),

                                // Security & Audit section
                                h('div', { style: { borderTop: '1px solid #e0e0e0', margin: '16px 0 12px', paddingTop: '12px' } },
                                    h('div', { style: { fontWeight: '600', fontSize: '13px', color: '#555', marginBottom: '10px' } }, 'Security & Audit'),
                                    h('div', { style: S.row2 },
                                        h('div', { style: S.formGroup },
                                            h('label', { style: S.label }, 'Write Security Level'),
                                            inp('writeSecurityLevel', 'text', { placeholder: 'e.g. Operator' }),
                                            h('div', { style: S.hint }, 'Required for tag writes, alarm acks. Empty = any authenticated user.'),
                                        ),
                                        h('div', { style: S.formGroup },
                                            h('label', { style: S.label }, 'Admin Security Level'),
                                            inp('adminSecurityLevel', 'text', { placeholder: 'e.g. Administrator' }),
                                            h('div', { style: S.hint }, 'Required for admin operations. Empty = any authenticated user.'),
                                        ),
                                    ),
                                    h('div', { style: S.formGroup },
                                        h('label', { style: S.label }, 'Audit Profile'),
                                        inp('auditProfileName', 'text', { placeholder: 'e.g. default' }),
                                        h('div', { style: S.hint }, 'Ignition audit profile for write operations. Empty = no auditing.'),
                                    ),
                                    h('div', { style: S.formGroup },
                                        h('label', { style: S.label }, 'Tag scope'),
                                        h('textarea', Object.assign({ style: S.textarea, rows: 3,
                                            placeholder: '[default]Launch/**\n[default]Launch/Clock/*\n[default]Launch/Clock/Phase' }, field('tagPathPatterns'))),
                                        h('div', { style: S.hint }, 'One pattern per line: /** = folder and everything below, /* = direct children, none = one tag. Blank = unrestricted.'),
                                    ),
                                ),

                                // Test connection (edit mode only)
                                isEdit && h('div', { style: { marginTop: '8px' } },
                                    h('button', {
                                        type: 'button',
                                        style: S.btn,
                                        onClick: handleTest,
                                        disabled: testing,
                                    }, testing ? 'Testing…' : 'Test Connection'),
                                    testResult && h('div', {
                                        style: Object.assign({}, S.testResult,
                                            testResult.success ? S.testOk : S.testFail),
                                    },
                                        testResult.success ? '✓ ' : '✗ ',
                                        testResult.message,
                                    ),
                                ),
                            ),
                            h('div', { style: S.modalFooter },
                                h('button', { type: 'button', style: S.btn, onClick: onClose }, 'Cancel'),
                                h('button', {
                                    type: 'submit',
                                    style: Object.assign({}, S.btn, S.btnPrimary),
                                    disabled: saving,
                                }, saving ? 'Saving…' : (isEdit ? 'Save' : 'Add Site')),
                            )
                        )
                    )
                );
            }

            // ── Site Row ─────────────────────────────────────────────────────
            function SiteRow(props) {
                var site = props.site;
                var onEdit = props.onEdit;
                var onDelete = props.onDelete;
                var onSync = props.onSync;
                var syncingId = props.syncingId;

                var _h = useState(false);
                var hovered = _h[0];
                var setHovered = _h[1];

                var sync = site.sync || {};
                var isSyncing = syncingId === site.id;

                return h('tr', {
                    style: hovered ? S.trHover : {},
                    onMouseEnter: function () { setHovered(true); },
                    onMouseLeave: function () { setHovered(false); },
                },
                    h('td', { style: S.td },
                        h('strong', null, site.name),
                        site.tagPathPatterns && site.tagPathPatterns.trim()
                            ? h('span', { style: Object.assign({}, S.badge, S.badgeSyncing, { marginLeft: '6px' }),
                                title: site.tagPathPatterns }, 'scoped')
                            : null,
                    ),
                    h('td', { style: S.td },
                        h('code', { style: { fontSize: '12px' } }, site.mountPath),
                    ),
                    h('td', { style: S.td },
                        h('span', { style: { fontSize: '12px', color: '#555' } },
                            site.repoUrl
                                ? site.repoUrl.replace(/^https?:\/\//, '').substring(0, 40) +
                                  (site.repoUrl.length > 47 ? '…' : '')
                                : '—',
                        ),
                        ' ',
                        site.branch && h('span', { style: Object.assign({}, S.badge, S.badgeOff) },
                            site.branch),
                    ),
                    h('td', { style: S.td }, site.enabled
                        ? h('span', { style: Object.assign({}, S.badge, S.badgeOk) }, 'Yes')
                        : h('span', { style: Object.assign({}, S.badge, S.badgeOff) }, 'No')
                    ),
                    h('td', { style: S.td }, syncBadge(sync)),
                    h('td', { style: S.td },
                        sync.commitHash
                            ? h('code', { style: { fontSize: '11px' } },
                                sync.commitHash.substring(0, 8))
                            : '—',
                    ),
                    h('td', { style: S.td },
                        sync.lastSyncTime
                            ? h('span', { style: { fontSize: '11px', color: '#666' } },
                                formatTime(sync.lastSyncTime))
                            : '—',
                    ),
                    h('td', { style: Object.assign({}, S.td, { whiteSpace: 'nowrap' }) },
                        h('button', {
                            style: Object.assign({}, S.btn, S.btnSmall),
                            onClick: function () { onEdit(site); },
                            title: 'Edit site configuration',
                        }, 'Edit'),
                        h('button', {
                            style: Object.assign({}, S.btn, S.btnSmall, isSyncing ? { opacity: 0.6 } : {}),
                            onClick: function () { onSync(site.id); },
                            disabled: isSyncing,
                            title: 'Trigger immediate git sync',
                        }, isSyncing ? 'Syncing…' : 'Sync'),
                        h('button', {
                            style: Object.assign({}, S.btn, S.btnSmall, S.btnDanger),
                            onClick: function () { onDelete(site); },
                            title: 'Delete this site',
                        }, 'Delete'),
                    ),
                );
            }

            // ── Gateway Settings panel ───────────────────────────────────────
            function SettingsPanel(props) {
                var showToast = props.showToast;

                var _f = useState(null);
                var form = _f[0];
                var setForm = _f[1];

                var _sv = useState(false);
                var saving = _sv[0];
                var setSaving = _sv[1];

                useEffect(function () {
                    apiFetch(SETTINGS_URL)
                        .then(function (data) { setForm(data); })
                        .catch(function (err) {
                            showToast('Failed to load settings: ' + err.message, 'error');
                        });
                }, []);

                function field(key, type) {
                    return {
                        type: type,
                        style: S.input,
                        value: form[key] != null ? form[key] : '',
                        onChange: function (e) {
                            var val = type === 'number' ? parseInt(e.target.value, 10) || 0 : e.target.value;
                            setForm(function (prev) {
                                var next = Object.assign({}, prev);
                                next[key] = val;
                                return next;
                            });
                        },
                    };
                }

                function handleSave(e) {
                    e.preventDefault();
                    setSaving(true);
                    apiFetch(SETTINGS_URL, { method: 'PUT', body: JSON.stringify(form) })
                        .then(function (data) {
                            setForm(data);
                            showToast('Gateway settings saved.', 'success');
                        })
                        .catch(function (err) {
                            showToast('Save settings failed: ' + err.message, 'error');
                        })
                        .finally(function () { setSaving(false); });
                }

                if (!form) return null;

                return h('form', {
                    onSubmit: handleSave,
                    style: { border: '1px solid #e0e0e0', borderRadius: '4px', padding: '12px 16px', marginBottom: '20px' },
                },
                    h('div', { style: { fontWeight: '600', fontSize: '13px', color: '#555', marginBottom: '10px' } }, 'Gateway Settings'),
                    h('div', { style: S.row2 },
                        h('div', { style: S.formGroup },
                            h('label', { style: S.label }, 'Admin Security Level'),
                            h('input', field('adminSecurityLevel', 'text')),
                            h('div', { style: S.hint }, 'Blank = REST site management denied (ADR-004)'),
                        ),
                        h('div', { style: S.formGroup },
                            h('label', { style: S.label }, 'Default Audit Profile'),
                            h('input', field('defaultAuditProfile', 'text')),
                            h('div', { style: S.hint }, 'Fallback when a site has no audit profile.'),
                        ),
                    ),
                    h('div', { style: S.formGroup },
                        h('label', { style: S.label }, 'Login Auth Profile'),
                        h('input', field('loginAuthProfile', 'text')),
                        h('div', { style: S.hint }, 'User source used by POST /auth/login.'),
                    ),
                    h('div', { style: S.row2 },
                        h('div', { style: S.formGroup },
                            h('label', { style: S.label }, 'Login Max Failures'),
                            h('input', Object.assign(field('loginMaxFailures', 'number'), { min: 1 })),
                            h('div', { style: S.hint }, 'Failures per user and IP before lockout.'),
                        ),
                        h('div', { style: S.formGroup },
                            h('label', { style: S.label }, 'Login Lockout Seconds'),
                            h('input', Object.assign(field('loginLockoutSeconds', 'number'), { min: 0 })),
                            h('div', { style: S.hint }, 'Lockout duration after max failures.'),
                        ),
                    ),
                    h('button', {
                        type: 'submit',
                        style: Object.assign({}, S.btn, S.btnPrimary, { marginLeft: 0 }),
                        disabled: saving,
                    }, saving ? 'Saving…' : 'Save Settings'),
                );
            }

            // ── Main Page Component ──────────────────────────────────────────
            function SitesPage(props) {
                var _sites = useState([]);
                var sites = _sites[0];
                var setSites = _sites[1];

                var _loading = useState(true);
                var loading = _loading[0];
                var setLoading = _loading[1];

                var _err = useState(null);
                var loadError = _err[0];
                var setLoadError = _err[1];

                // Editing: null = closed, object = editing, 'new' = adding
                var _edit = useState(null);
                var editTarget = _edit[0];
                var setEditTarget = _edit[1];

                // Delete confirm
                var _del = useState(null);
                var deleteTarget = _del[0];
                var setDeleteTarget = _del[1];

                // In-progress sync site IDs
                var _syncing = useState(null);
                var syncingId = _syncing[0];
                var setSyncingId = _syncing[1];

                // Toast
                var _toast = useState(null);
                var toast = _toast[0];
                var setToast = _toast[1];

                var toastTimer = useRef(null);

                function showToast(msg, kind) {
                    clearTimeout(toastTimer.current);
                    setToast({ msg: msg, kind: kind || 'info' });
                    toastTimer.current = setTimeout(function () { setToast(null); }, 4000);
                }

                function loadSites() {
                    return apiFetch(API_BASE)
                        .then(function (data) {
                            setSites(Array.isArray(data) ? data : []);
                            setLoadError(null);
                        })
                        .catch(function (err) {
                            setLoadError(err.message || 'Failed to load sites');
                        })
                        .finally(function () {
                            setLoading(false);
                        });
                }

                // Initial load + periodic refresh
                useEffect(function () {
                    loadSites();
                    var timer = setInterval(loadSites, POLL_MS);
                    return function () {
                        clearInterval(timer);
                        clearTimeout(toastTimer.current);
                    };
                }, []);

                function handleSave(saved) {
                    setEditTarget(null);
                    showToast('Site saved successfully.', 'success');
                    loadSites();
                }

                function handleDelete(site) {
                    apiFetch(API_BASE + '/' + site.id, { method: 'DELETE' })
                        .then(function () {
                            setDeleteTarget(null);
                            showToast('Site "' + site.name + '" deleted.', 'success');
                            loadSites();
                        })
                        .catch(function (err) {
                            setDeleteTarget(null);
                            showToast('Delete failed: ' + err.message, 'error');
                        });
                }

                function handleSync(siteId) {
                    setSyncingId(siteId);
                    apiFetch(API_BASE + '/' + siteId + '/sync', { method: 'POST' })
                        .then(function () {
                            setSyncingId(null);
                            showToast('Sync triggered.', 'info');
                            setTimeout(loadSites, 1500);
                        })
                        .catch(function (err) {
                            setSyncingId(null);
                            showToast('Sync failed: ' + err.message, 'error');
                        });
                }

                return h('div', { style: S.page },
                    // Header
                    h('div', { style: S.header },
                        h('h1', { style: S.title }, 'Ignite Sites'),
                        h('button', {
                            style: Object.assign({}, S.btn, S.btnPrimary),
                            onClick: function () { setEditTarget('new'); },
                        }, '+ Add Site'),
                    ),

                    // Gateway settings
                    h(SettingsPanel, { showToast: showToast }),

                    // Error banner
                    loadError && h('div', {
                        style: Object.assign({}, S.testResult, S.testFail, { marginBottom: '12px' })
                    }, 'Error loading sites: ' + loadError),

                    // Loading state
                    loading && h('div', { style: S.empty }, 'Loading…'),

                    // Sites table
                    !loading && sites.length === 0 && !loadError && h('div', { style: S.empty },
                        h('p', { style: { fontSize: '16px', marginBottom: '8px' } }, 'No sites configured.'),
                        h('p', { style: { color: '#aaa' } }, 'Click "Add Site" to create your first site.'),
                    ),

                    !loading && sites.length > 0 && h('div', { style: { overflowX: 'auto' } },
                        h('table', { style: S.table },
                            h('thead', null,
                                h('tr', null,
                                    h('th', { style: S.th }, 'Name'),
                                    h('th', { style: S.th }, 'Mount Path'),
                                    h('th', { style: S.th }, 'Repo / Branch'),
                                    h('th', { style: S.th }, 'Enabled'),
                                    h('th', { style: S.th }, 'Sync State'),
                                    h('th', { style: S.th }, 'Commit'),
                                    h('th', { style: S.th }, 'Last Sync'),
                                    h('th', { style: S.th }, 'Actions'),
                                )
                            ),
                            h('tbody', null,
                                sites.map(function (site) {
                                    return h(SiteRow, {
                                        key: site.id,
                                        site: site,
                                        onEdit: setEditTarget,
                                        onDelete: setDeleteTarget,
                                        onSync: handleSync,
                                        syncingId: syncingId,
                                    });
                                })
                            )
                        )
                    ),

                    // Add/Edit modal
                    (editTarget === 'new' || (editTarget && editTarget !== 'new')) &&
                        h(SiteForm, {
                            site: editTarget === 'new' ? null : editTarget,
                            onClose: function () { setEditTarget(null); },
                            onSave: handleSave,
                        }),

                    // Delete confirm dialog
                    deleteTarget && h(ConfirmDialog, {
                        open: true,
                        message: 'Delete site "' + (deleteTarget.name || '') + '"? This cannot be undone.',
                        onConfirm: function () { handleDelete(deleteTarget); },
                        onCancel: function () { setDeleteTarget(null); },
                    }),

                    // Toast notification
                    toast && h(Toast, { message: toast.msg, kind: toast.kind }),
                );
            }

            // ── Export ───────────────────────────────────────────────────────
            _export('ignite-sites', SitesPage);
        }
    };
});
