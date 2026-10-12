/**
 * Ignite Gateway Home Page — Launch
 *
 * SystemJS module exported as "ignite-launch".
 * Loaded by Ignition's gateway UI when the "Home > Ignite > Launch" nav entry is visited.
 *
 * Reads GET /data/ignite/launch (web session + ACCESS) and shows a card per enabled site.
 * Auto-refreshes every 15 seconds.
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
            var useState  = React.useState;
            var useEffect = React.useEffect;

            // ── Constants ────────────────────────────────────────────────────
            var API_URL = '/data/ignite/launch';
            var SITES_CONFIG_PATH = '/app/connections/ignite/sites';
            var POLL_MS = 15000;

            // ── Styles ───────────────────────────────────────────────────────
            var S = {
                page: {
                    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    fontSize: '14px',
                    color: '#333',
                    padding: '20px',
                },
                title: { fontSize: '18px', fontWeight: '600', margin: '0 0 16px 0' },
                grid: {
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                    gap: '16px',
                },
                card: {
                    border: '1px solid #d0d3d8',
                    borderRadius: '6px',
                    background: '#fff',
                    padding: '16px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '8px',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
                },
                cardHead: { display: 'flex', alignItems: 'center', gap: '8px' },
                dot: { display: 'inline-block', width: '10px', height: '10px', borderRadius: '50%', flexShrink: 0 },
                name: { fontSize: '16px', fontWeight: '600', margin: 0 },
                url: { fontSize: '12px', color: '#666', fontFamily: 'monospace', wordBreak: 'break-all' },
                meta: { fontSize: '12px', color: '#666' },
                btn: {
                    display: 'inline-block',
                    alignSelf: 'flex-start',
                    marginTop: '4px',
                    padding: '6px 14px',
                    border: '1px solid #1a5f8f',
                    borderRadius: '4px',
                    background: '#1e6fa8',
                    color: '#fff',
                    fontSize: '13px',
                    lineHeight: '1.4',
                    textDecoration: 'none',
                    cursor: 'pointer',
                },
                empty: { textAlign: 'center', padding: '48px 24px', color: '#888' },
                error: {
                    padding: '10px 14px', borderRadius: '4px', marginBottom: '12px',
                    background: '#f8d7da', color: '#721c24', fontSize: '13px',
                },
            };

            var DOT_COLORS = {
                OK: '#28a745',
                ERROR: '#dc3545',
                SYNCING: '#1e6fa8',
                PENDING: '#e0a800',
            };

            // ── Utilities ────────────────────────────────────────────────────
            function relativeTime(ts) {
                if (!ts) return 'never';
                var t = new Date(ts).getTime();
                if (isNaN(t)) return 'never';
                var sec = Math.max(0, Math.round((Date.now() - t) / 1000));
                if (sec < 60) return 'just now';
                var min = Math.round(sec / 60);
                if (min < 60) return min + ' min ago';
                var hr = Math.round(min / 60);
                if (hr < 24) return hr + ' h ago';
                return Math.round(hr / 24) + ' d ago';
            }

            function loadLaunch() {
                return fetch(API_URL, {
                    headers: { Accept: 'application/json' },
                    credentials: 'same-origin',
                }).then(function (res) {
                    if (!res.ok) { throw new Error('HTTP ' + res.status); }
                    return res.json();
                });
            }

            // ── Components ───────────────────────────────────────────────────
            function SiteCard(props) {
                var site = props.site;
                var state = (site.syncState || 'PENDING').toUpperCase();
                var openUrl = window.location.origin + site.url;
                return h('div', { style: S.card },
                    h('div', { style: S.cardHead },
                        h('span', {
                            style: Object.assign({}, S.dot, { background: DOT_COLORS[state] || DOT_COLORS.PENDING }),
                            title: state,
                        }),
                        h('h3', { style: S.name }, site.name),
                    ),
                    h('div', { style: S.url }, site.url),
                    h('div', { style: S.meta }, state + ' · last sync ' + relativeTime(site.lastSyncTime)),
                    h('a', {
                        href: openUrl,
                        target: '_blank',
                        rel: 'noopener',
                        style: S.btn,
                    }, 'Open'),
                );
            }

            function LaunchPage() {
                var _sites = useState(null);
                var sites = _sites[0];
                var setSites = _sites[1];
                var _err = useState(null);
                var error = _err[0];
                var setError = _err[1];

                useEffect(function () {
                    var cancelled = false;
                    function refresh() {
                        loadLaunch()
                            .then(function (data) {
                                if (cancelled) return;
                                setSites((data && data.sites) || []);
                                setError(null);
                            })
                            .catch(function (e) {
                                if (cancelled) return;
                                setError(e.message || 'Failed to load sites');
                            });
                    }
                    refresh();
                    var timer = setInterval(refresh, POLL_MS);
                    return function () { cancelled = true; clearInterval(timer); };
                }, []);

                var body;
                if (sites === null && !error) {
                    body = h('div', { style: S.empty }, 'Loading…');
                } else if (sites === null) {
                    body = null;
                } else if (sites.length === 0) {
                    body = h('div', { style: S.empty },
                        'No enabled sites — add one under ',
                        h('a', { href: SITES_CONFIG_PATH }, 'Connections → Ignite → Sites'),
                    );
                } else {
                    body = h('div', { style: S.grid },
                        sites.map(function (s) {
                            return h(SiteCard, { key: s.mountPath, site: s });
                        }));
                }

                return h('div', { style: S.page },
                    h('h2', { style: S.title }, 'Ignite Sites'),
                    error && h('div', { style: S.error }, 'Could not load sites: ' + error),
                    body,
                );
            }

            _export('ignite-launch', LaunchPage);
        },
    };
});
