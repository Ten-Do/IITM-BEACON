/*
 * IITM Beacon — "Resend code" waits out the per-email code request limit
 * (decision 36).
 *
 * Vanilla JS, no framework. Loaded (deferred) only by the two login code
 * pages, submission/login-code.html and adminauth/login-code.html. The
 * server renders their "Resend code" button enabled, so without JavaScript
 * it works as before, and tells this script in data-resend-wait-seconds how
 * long one more code request for the same email takes to be allowed:
 *
 * - On load the button is disabled — the real disabled attribute: it has
 *   sent nothing yet, so nothing is lost, and a disabled button can't send
 *   its form — and its label counts down ("Resend code in 59s"), once a
 *   second. At zero it is enabled again with its own label back.
 * - The countdown runs by the clock, not by counting ticks, so a timer held
 *   back in a background tab catches up as soon as it fires again.
 * - It starts at page load, so a reload starts it over — as does a wrong
 *   code, whose answer re-renders the page — and so does a page the
 *   browser brings back from its back/forward cache (pageshow with
 *   event.persisted): the code may have been resent from the page left
 *   meanwhile. Waiting a little too long is harmless; too short would end on
 *   the server's rate-limited answer.
 * - A missing, zero or unreadable wait leaves the button as it is.
 *
 * The server still enforces the limit itself: this only keeps the page from
 * offering a request it would refuse.
 */
(function () {
    'use strict';

    var MILLIS_PER_SECOND = 1000;

    // {button, label, timer} for every button counting down.
    var cooldowns = [];

    function render(cooldown, deadline) {
        var remaining = Math.ceil((deadline - Date.now()) / MILLIS_PER_SECOND);
        if (remaining > 0) {
            cooldown.button.textContent = cooldown.label + ' in ' + remaining + 's';
            return;
        }
        window.clearInterval(cooldown.timer);
        cooldown.timer = null;
        cooldown.button.textContent = cooldown.label;
        cooldown.button.disabled = false;
    }

    function start(cooldown) {
        var seconds = parseInt(cooldown.button.getAttribute('data-resend-wait-seconds'), 10);
        if (!(seconds > 0)) {
            return;
        }
        var deadline = Date.now() + seconds * MILLIS_PER_SECOND;
        window.clearInterval(cooldown.timer);
        cooldown.button.disabled = true;
        render(cooldown, deadline);
        cooldown.timer = window.setInterval(function () {
            render(cooldown, deadline);
        }, MILLIS_PER_SECOND);
    }

    function startOverWhenRestored(event) {
        if (event.persisted) {
            cooldowns.forEach(start);
        }
    }

    document.querySelectorAll('button[data-resend-wait-seconds]').forEach(function (button) {
        var cooldown = {button: button, label: button.textContent.trim(), timer: null};
        cooldowns.push(cooldown);
        start(cooldown);
    });
    window.addEventListener('pageshow', startOverWhenRestored);
})();
