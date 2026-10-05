/*
 * IITM Beacon — reveal a testimonial's contact info in place
 * (UC-REVEAL-CONTACT).
 *
 * Vanilla JS, no framework. Loaded (deferred) by gallery/detail.html only
 * while the article shows its "Reveal contact info" button: the form of
 * templates/gallery/contact-card.html :: revealForm, inside the card marked
 * data-contact-card.
 *
 * Without this script the form is a plain POST, and the server answers with
 * the whole article (landing on #contact). With it, submitting the form
 * fetches the same URL with "X-Requested-With: fetch", to which the server
 * answers with the contact card alone; that card replaces the one holding
 * the form, in place — no navigation, no scrolling — and takes the focus
 * the removed button had. The server only answers requests from this site's
 * own pages (Origin / Sec-Fetch-Site), which a same-origin fetch satisfies.
 *
 * While the request runs the button shows a spinner and is marked busy and
 * disabled (aria-busy, aria-disabled — not the disabled attribute, which
 * would drop the keyboard focus to the page), and further submits are
 * ignored, so a double click sends one request. Any failure — 403, 404, a
 * server error, no network, an answer that isn't the card — shows a short
 * line under the button and hands the button back for another try.
 */
(function () {
    'use strict';

    var UNAVAILABLE = 'Contact info isn\'t available.';
    var FAILED = 'Couldn\'t load the contact info. Please try again.';

    function setBusy(button, busy) {
        if (busy) {
            button.setAttribute('aria-busy', 'true');
            button.setAttribute('aria-disabled', 'true');
        } else {
            button.removeAttribute('aria-busy');
            button.removeAttribute('aria-disabled');
        }
    }

    function showError(errorLine, message) {
        if (errorLine) {
            errorLine.textContent = message;
            errorLine.hidden = !message;
        }
    }

    // The card in the server's answer, or null if the answer is anything else.
    // A <template> parses it inertly: nothing in it loads or runs.
    function parseCard(html) {
        var template = document.createElement('template');
        template.innerHTML = html;
        var card = template.content.firstElementChild;
        return card && card.matches('[data-contact-card]') ? card : null;
    }

    function enhance(form) {
        var card = form.closest('[data-contact-card]');
        var button = form.querySelector('button[type="submit"]');
        var errorLine = form.querySelector('[data-contact-reveal-error]');
        if (!card || !button) {
            return;
        }
        var pending = false;

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (pending) {
                return;
            }
            pending = true;
            var failure = FAILED;
            setBusy(button, true);
            showError(errorLine, '');

            fetch(form.action, {
                method: 'POST',
                credentials: 'same-origin',
                cache: 'no-store',
                headers: {'X-Requested-With': 'fetch', 'Accept': 'text/html'}
            }).then(function (response) {
                if (response.status === 404) {
                    failure = UNAVAILABLE;
                }
                if (!response.ok || response.redirected) {
                    throw new Error('Contact request failed: ' + response.status);
                }
                return response.text();
            }).then(function (html) {
                var fresh = parseCard(html);
                if (!fresh) {
                    throw new Error('Unexpected contact response');
                }
                card.replaceWith(fresh);
                fresh.focus({preventScroll: true});
            }).catch(function () {
                pending = false;
                setBusy(button, false);
                showError(errorLine, failure);
            });
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll('.gallery-reveal-form'), enhance);
})();
