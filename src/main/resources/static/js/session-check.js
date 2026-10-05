/*
 * IITM Beacon — session check for pages that need a login.
 *
 * Vanilla JS, no framework. Loaded (deferred) only on pages that need an
 * admin or visitor login, via templates/layout/session-check.html, which
 * puts the session ping URL of the page's role on this script's own tag as
 * data-session-url.
 *
 * Whenever the tab becomes visible again, pings that URL. A 204 means the
 * session is alive (and the ping itself keeps it alive). A 401 (no or
 * expired session) or 403 (the browser's session now belongs to the other
 * role) reloads the page: the reload goes through the server's redirect to
 * the login page, and the login then returns to this page. Network errors
 * and any other status are ignored — the next return to the tab tries again.
 *
 * Deliberately reacts to the tab becoming visible and to nothing else.
 */
(function () {
    'use strict';

    // Set while a classic script (deferred or not) runs at top level, but no
    // longer inside event handlers — so it must be read right here.
    var script = document.currentScript;
    var pingUrl = script ? script.getAttribute('data-session-url') : null;
    if (!pingUrl) {
        return;
    }

    // A reload on a login page would only land on the login page again.
    var path = window.location.pathname;
    if (path.indexOf('/admin/login') === 0 || path.indexOf('/submissions/login') === 0) {
        return;
    }

    var checking = false;

    function checkSession() {
        if (checking) {
            return;
        }
        checking = true;
        fetch(pingUrl, {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: {Accept: 'application/json'}
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                window.location.reload();
            }
        }).catch(function () {
            // Offline or similar: nothing to decide yet.
        }).then(function () {
            checking = false;
        });
    }

    function checkWhenVisible() {
        if (document.visibilityState === 'visible') {
            checkSession();
        }
    }

    document.addEventListener('visibilitychange', checkWhenVisible);
})();
