/*
 * IITM Beacon — burger menu for the header nav on phones.
 *
 * Vanilla JS, no framework. Loaded (deferred) on every page by
 * templates/layout/shell.html's head. Driven by markup only, so any header
 * can reuse it through templates/layout/nav-toggle.html: every element with
 * data-nav-toggle is a button that opens and closes the element whose id is
 * in its aria-controls, keeping its aria-expanded in step.
 *
 * Progressive enhancement: this script marks the button ready and the menu
 * collapsible; beacon.css only shows the button, and only hides a closed
 * menu, on narrow screens and once those classes are set. Without it the
 * button stays hidden and the nav stays visible.
 *
 * An open menu closes on Escape (focus goes back to its button), on a click
 * or tap anywhere outside it and its button, and when one of its links is
 * followed.
 */
(function () {
    'use strict';

    var TOGGLE_READY = 'nav-toggle--ready';
    var MENU_COLLAPSIBLE = 'nav-menu--collapsible';
    var MENU_OPEN = 'nav-menu--open';

    var toggles = [];

    function menuOf(button) {
        var id = button.getAttribute('aria-controls');
        return id ? document.getElementById(id) : null;
    }

    function isOpen(button) {
        return button.getAttribute('aria-expanded') === 'true';
    }

    function setOpen(button, open) {
        button.setAttribute('aria-expanded', String(open));
        menuOf(button).classList.toggle(MENU_OPEN, open);
    }

    function wire(button) {
        var menu = menuOf(button);
        if (!menu || toggles.indexOf(button) >= 0) {
            // Nothing to open: leave the button hidden and the nav as it is.
            return;
        }
        toggles.push(button);
        setOpen(button, false);
        menu.classList.add(MENU_COLLAPSIBLE);
        button.classList.add(TOGGLE_READY);

        button.addEventListener('click', function () {
            setOpen(button, !isOpen(button));
        });
        menu.addEventListener('click', function (event) {
            if (event.target.closest('a')) {
                setOpen(button, false);
            }
        });
    }

    function closeOnEscape(event) {
        if (event.key !== 'Escape') {
            return;
        }
        toggles.forEach(function (button) {
            if (isOpen(button)) {
                setOpen(button, false);
                button.focus();
            }
        });
    }

    function closeOnClickOutside(event) {
        toggles.forEach(function (button) {
            if (isOpen(button) && !button.contains(event.target) && !menuOf(button).contains(event.target)) {
                setOpen(button, false);
            }
        });
    }

    function init() {
        document.querySelectorAll('[data-nav-toggle]').forEach(wire);
        if (toggles.length > 0) {
            document.addEventListener('keydown', closeOnEscape);
            document.addEventListener('click', closeOnClickOutside);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
