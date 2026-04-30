/* ════════════════════════════════════════════════════════════════════════════
 *  capacitor-bridge.js
 *
 *  Mobile-only behaviours wrapped in a strict feature-detect.
 *  This file is included by every Thymeleaf page so the SAME deployed web
 *  build powers both the regular browser (Render) and the wrapped Capacitor
 *  mobile app — with zero regression on web.
 *
 *  When running in a normal browser:
 *    • window.Capacitor is undefined  →  the entire script no-ops
 *    • Web behaviour is byte-for-byte identical to before
 *
 *  When running inside the Capacitor WebView (Android / iOS):
 *    1. Marks <body> with `class="capacitor-native"` for CSS hooks
 *    2. Injects a tiny CSS rule that pushes fixed headers below the iPhone
 *       notch / Android status bar (uses env(safe-area-inset-*))
 *    3. Adds `viewport-fit=cover` to the viewport meta so safe-area variables
 *       actually return non-zero values
 *    4. Listens for the Android hardware back button:
 *         • Goes back in history when possible
 *         • At the dashboard / login root, asks the user to confirm exit
 *           instead of silently closing the app
 * ════════════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';

    // Hard guard: only run inside a real Capacitor native shell.
    if (typeof window === 'undefined') return;
    if (!window.Capacitor || typeof window.Capacitor.isNativePlatform !== 'function'
        || !window.Capacitor.isNativePlatform()) {
        return;
    }

    // ── 1. Body class hook for CSS rules ────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        document.body.classList.add('capacitor-native');
        document.body.classList.add('capacitor-' + window.Capacitor.getPlatform());
    });

    // ── 2. Inject Safe-Area padding stylesheet ──────────────────────────────
    // Pushes any fixed/sticky header (and any explicit `.safe-area` element)
    // below the system inset on iOS & Android.
    var css = ''
        + '.capacitor-native { padding-top: env(safe-area-inset-top); }'
        + '.capacitor-native .header-bar,'
        + '.capacitor-native .navbar,'
        + '.capacitor-native .sr-top-banner,'
        + '.capacitor-native [data-safe-area="top"] {'
        + '    padding-top: calc(8px + env(safe-area-inset-top, 0px)) !important;'
        + '}'
        + '.capacitor-native [data-safe-area="bottom"],'
        + '.capacitor-native .footer-bar,'
        + '.capacitor-native .sr-controls {'
        + '    padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px)) !important;'
        + '}'
        + '.capacitor-native [data-safe-area="left"]  { padding-left:  env(safe-area-inset-left, 0px)  !important; }'
        + '.capacitor-native [data-safe-area="right"] { padding-right: env(safe-area-inset-right, 0px) !important; }';
    var style = document.createElement('style');
    style.id = 'capacitor-safe-area-css';
    style.textContent = css;
    document.head.appendChild(style);

    // ── 3. Ensure viewport-fit=cover so env(safe-area-inset-*) is non-zero ──
    var meta = document.querySelector('meta[name="viewport"]');
    if (meta) {
        var content = meta.getAttribute('content') || '';
        if (!/viewport-fit\s*=/.test(content)) {
            meta.setAttribute('content', content + (content ? ', ' : '') + 'viewport-fit=cover');
        }
    } else {
        var m = document.createElement('meta');
        m.name = 'viewport';
        m.content = 'width=device-width, initial-scale=1, viewport-fit=cover';
        document.head.appendChild(m);
    }

    // ── 4. Android hardware back-button handling ────────────────────────────
    // Capacitor exposes its plugin through `window.Capacitor.Plugins.App` only
    // after the native bridge is ready. We poll briefly to avoid a race.
    function attachBackButton() {
        try {
            var App = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.App;
            if (!App || typeof App.addListener !== 'function') return false;

            App.addListener('backButton', function (ev) {
                // "ev.canGoBack" is set by the native code based on WebView history
                if (ev && ev.canGoBack) {
                    window.history.back();
                    return;
                }
                // Root page — ask before closing
                var atLoginOrRoot = /^\/?(login|admin\/login|host\/dashboard|student\/dashboard)\/?$/
                                    .test(window.location.pathname) || window.location.pathname === '/';
                if (atLoginOrRoot) {
                    if (window.confirm('Exit Host Mtng?')) {
                        App.exitApp();
                    }
                } else {
                    window.history.back();
                }
            });
            return true;
        } catch (e) {
            console.warn('[capacitor-bridge] back-button setup failed:', e);
            return false;
        }
    }

    // Try immediately, then a couple of retries while the native bridge boots.
    var tries = 0;
    var poll = setInterval(function () {
        tries++;
        if (attachBackButton() || tries > 20) clearInterval(poll);
    }, 150);

    console.log('[capacitor-bridge] Active. Platform:', window.Capacitor.getPlatform());
})();
