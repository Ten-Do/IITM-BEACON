/*
 * IITM Beacon — fullscreen photo viewer (UC-VIEW-PHOTOS-FULLSCREEN).
 *
 * Vanilla JS, no framework, no network calls. Builds an ordered list of
 * every [data-photo-viewer-item] on the page (DOM order == server-rendered
 * article order, already correct), then on click clones the
 * #photo-viewer-template <template> (see layout/shell.html) into a live
 * overlay appended to <body>, wires prev/next/close, and removes the
 * overlay again on close (rather than keeping a hidden singleton around).
 */
(function () {
    'use strict';

    function collectPhotos() {
        var items = document.querySelectorAll('[data-photo-viewer-item]');
        var photos = [];
        for (var i = 0; i < items.length; i++) {
            photos.push({
                url: items[i].getAttribute('data-url'),
                caption: items[i].getAttribute('data-caption') || ''
            });
        }
        return photos;
    }

    function openViewer(photos, startIndex) {
        var template = document.getElementById('photo-viewer-template');
        if (!template || photos.length === 0) {
            return;
        }

        var fragment = template.content.cloneNode(true);
        var overlay = fragment.querySelector('.photo-viewer-overlay');
        document.body.appendChild(fragment);

        var image = overlay.querySelector('.photo-viewer-image');
        var captionText = overlay.querySelector('.photo-viewer-caption-text');
        var captionCount = overlay.querySelector('.photo-viewer-caption-count');
        var closeButton = overlay.querySelector('.photo-viewer-close');
        var prevButton = overlay.querySelector('.photo-viewer-prev');
        var nextButton = overlay.querySelector('.photo-viewer-next');

        var currentIndex = startIndex;

        function render() {
            var photo = photos[currentIndex];
            image.setAttribute('src', photo.url);
            image.setAttribute('alt', photo.caption);
            captionText.textContent = photo.caption;
            captionCount.textContent = (currentIndex + 1) + ' / ' + photos.length;
        }

        function close() {
            overlay.remove();
            document.removeEventListener('keydown', onKeyDown);
        }

        function showPrev() {
            currentIndex = (currentIndex - 1 + photos.length) % photos.length;
            render();
        }

        function showNext() {
            currentIndex = (currentIndex + 1) % photos.length;
            render();
        }

        function onKeyDown(event) {
            if (event.key === 'Escape') {
                close();
            }
        }

        closeButton.addEventListener('click', close);
        prevButton.addEventListener('click', showPrev);
        nextButton.addEventListener('click', showNext);
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) {
                close();
            }
        });
        document.addEventListener('keydown', onKeyDown);

        render();
    }

    document.addEventListener('DOMContentLoaded', function () {
        var photos = collectPhotos();
        var items = document.querySelectorAll('[data-photo-viewer-item]');
        for (var i = 0; i < items.length; i++) {
            (function (index) {
                items[index].addEventListener('click', function () {
                    openViewer(photos, index);
                });
            })(i);
        }
    });
})();
