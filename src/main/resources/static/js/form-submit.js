/*
 * IITM Beacon — a busy state for the button that submitted a form, and no
 * second submit while the first is on its way.
 *
 * Vanilla JS, no framework. Loaded (deferred) on every page by
 * templates/layout/shell.html's head. One submit listener on the document,
 * in the bubble phase, so every form's own listeners run before it:
 *
 * - A submit another script has taken over (event.defaultPrevented) is left
 *   alone: gallery/contact-card.html's reveal form, which contact-reveal.js
 *   sends itself and marks busy with its own spinner.
 * - A further submit of a form already being sent is prevented, so a double
 *   click (or a second Enter) sends it once.
 * - Otherwise the form is marked as being sent, and the button that
 *   submitted it (event.submitter; without one, the form's first submit
 *   button) is marked busy and disabled — aria-busy and aria-disabled, not
 *   the disabled attribute, which would drop the keyboard focus to the page
 *   and leave the button's name and value out of the submission. beacon.css
 *   shows a spinner on it and no hover or press effect.
 *
 * A form that opens its answer in another window or frame (a target other
 * than _self) leaves this page as it is, so it is never marked.
 *
 * The page normally goes away when the answer arrives. If the browser brings
 * it back from its back/forward cache (pageshow with event.persisted), every
 * form and button marked here is reset, so the form can be sent again — as
 * submission-form.js re-enables its photo inputs.
 */
(function () {
    'use strict';

    // {form, button} for every form submitted from this page.
    var sending = [];

    function isSending(form) {
        return sending.some(function (entry) {
            return entry.form === form;
        });
    }

    function opensElsewhere(form) {
        var target = form.getAttribute('target');
        return Boolean(target) && target !== '_self';
    }

    function firstSubmitButton(form) {
        var controls = form.elements;
        for (var i = 0; i < controls.length; i++) {
            if (controls[i].type === 'submit') {
                return controls[i];
            }
        }
        return null;
    }

    function onSubmit(event) {
        var form = event.target;
        if (event.defaultPrevented || opensElsewhere(form)) {
            return;
        }
        if (isSending(form)) {
            event.preventDefault();
            return;
        }
        var button = event.submitter || firstSubmitButton(form);
        sending.push({form: form, button: button});
        if (button) {
            button.setAttribute('aria-busy', 'true');
            button.setAttribute('aria-disabled', 'true');
        }
    }

    function resetWhenRestored(event) {
        if (!event.persisted) {
            return;
        }
        sending.forEach(function (entry) {
            if (entry.button) {
                entry.button.removeAttribute('aria-busy');
                entry.button.removeAttribute('aria-disabled');
            }
        });
        sending = [];
    }

    document.addEventListener('submit', onSubmit);
    window.addEventListener('pageshow', resetWhenRestored);
})();
