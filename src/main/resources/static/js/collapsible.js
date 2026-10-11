/*
 * IITM Beacon — "show more" disclosures: a button that opens and closes the
 * rows a list keeps hidden (the homepage dashboard's "+ N more countries" and
 * "Show all (N more)", decision 30).
 *
 * Vanilla JS, no framework. Loaded (deferred) by analytics/dashboard.html
 * only. Driven by markup only, like nav-toggle.js: every element with
 * data-collapsible-toggle is a button that opens and closes the element
 * whose id is in its aria-controls, keeping its aria-expanded in step.
 * Open, that element has the collapsible--open class; beacon.css animates
 * it open and closed (its grid row from 0fr to 1fr) and swaps the button's
 * label by aria-expanded. The button sits right after the rows it controls,
 * so it glides down as they open.
 *
 * Without JavaScript css/noscript.css shows every hidden row and hides the
 * buttons, which would open nothing.
 */
(function () {
    'use strict';

    var OPEN = 'collapsible--open';

    function wire(button) {
        var id = button.getAttribute('aria-controls');
        var target = id ? document.getElementById(id) : null;
        if (!target) {
            // Nothing to open: the button stays a no-op.
            return;
        }
        button.addEventListener('click', function () {
            var open = button.getAttribute('aria-expanded') !== 'true';
            button.setAttribute('aria-expanded', String(open));
            target.classList.toggle(OPEN, open);
        });
    }

    function init() {
        document.querySelectorAll('[data-collapsible-toggle]').forEach(wire);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
