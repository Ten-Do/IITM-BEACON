/*
 * IITM Beacon — fullscreen photo viewer (UC-VIEW-PHOTOS-FULLSCREEN), built on
 * PhotoSwipe 5 (WebJar org.webjars.npm:photoswipe, loaded at its version-less
 * URL; the ~50 KB core only on first open). An ES module: load it with
 * <script type="module">, through layout/photo-viewer.html :: assets.
 *
 * Reads the markup of layout/photo-viewer.html :: photo — every
 * [data-photo-gallery] element is one gallery of a[data-photo-viewer-item]
 * links (href = full-size photo, data-pswp-width/-height = its size when
 * known, data-caption = its caption), each followed by the photo's tag chips
 * (.photo-tag-chip) inside the same <figure>.
 *
 * On top of what PhotoSwipe does by itself (keyboard ←/→/Esc, swipe, pinch
 * and click zoom, focus trap):
 *   - a caption bar at the bottom: caption, tag chips and an "i / n"
 *     counter (PhotoSwipe's own counter, top left, is off);
 *   - the prev/next arrows sit in the bottom corners, level with the
 *     caption, on touch screens too — that part is CSS (beacon.css,
 *     ".pswp.beacon-pswp"), so they never move with the photo's size;
 *   - paddingFn keeps the photo clear of the close button and of the caption
 *     bar, whose height it measures for each photo;
 *   - a photo of unknown size (a legacy photo stored before sizes were
 *     recorded) takes it from its thumbnail, which for such a photo is the
 *     full image; if that isn't loaded yet, from the photo itself once it is.
 */
import PhotoSwipeLightbox from '/webjars/photoswipe/dist/photoswipe-lightbox.esm.min.js';

const GALLERY_SELECTOR = '[data-photo-gallery]';
const ITEM_SELECTOR = 'a[data-photo-viewer-item]';
const TAG_CHIP_SELECTOR = '.photo-tag-chip';

function icon(shapes) {
    return '<svg aria-hidden="true" class="pswp__icn photo-viewer-icon" viewBox="0 0 24 24"'
        + ' width="22" height="22">' + shapes + '</svg>';
}

function textElement(tagName, className, text) {
    const element = document.createElement(tagName);
    element.className = className;
    element.textContent = text;
    return element;
}

function tagsOf(link) {
    const figure = link.closest('figure');
    if (!figure) {
        return [];
    }
    return Array.from(figure.querySelectorAll(TAG_CHIP_SELECTOR), (chip) => chip.textContent.trim())
        .filter((tag) => tag !== '');
}

/** Fills a caption bar (the live one, or the hidden one used to measure) for the photo behind link. */
function fillCaption(bar, link, counterText) {
    const children = [];
    const caption = link.dataset.caption || '';
    if (caption !== '') {
        children.push(textElement('p', 'photo-viewer-caption-text', caption));
    }
    const tags = tagsOf(link);
    if (tags.length > 0) {
        const list = document.createElement('ul');
        list.className = 'photo-tag-list';
        list.setAttribute('aria-label', 'Photo tags');
        tags.forEach((tag) => list.append(textElement('li', 'photo-tag-chip', tag)));
        children.push(list);
    }
    children.push(textElement('p', 'photo-viewer-counter', counterText));
    bar.replaceChildren(...children);
}

// -- Keeping the photo clear of the controls --

let measuringBar;
const barHeights = new WeakMap(); // link -> { viewportWidth, height }

/** Height of the caption bar for the photo behind link, at the current viewport width. */
function captionBarHeight(link, viewportWidth) {
    const known = barHeights.get(link);
    if (known && known.viewportWidth === viewportWidth) {
        return known.height;
    }
    if (!measuringBar) {
        measuringBar = document.createElement('div');
        measuringBar.className = 'photo-viewer-caption photo-viewer-caption--measure';
        measuringBar.setAttribute('aria-hidden', 'true');
        document.body.append(measuringBar);
    }
    fillCaption(measuringBar, link, '1 / 1');
    const height = Math.ceil(measuringBar.getBoundingClientRect().height);
    barHeights.set(link, { viewportWidth, height });
    return height;
}

/** A px length from a custom property on :root (see the photo viewer section of beacon.css). */
function cssPixels(propertyName) {
    return parseFloat(getComputedStyle(document.documentElement).getPropertyValue(propertyName)) || 0;
}

function paddingFn(viewportSize, itemData) {
    const top = cssPixels('--photo-viewer-top-space');
    const side = cssPixels('--photo-viewer-side-space');
    const bottom = itemData.element ? captionBarHeight(itemData.element, viewportSize.x) : top;
    return { top, bottom, left: side, right: side };
}

// -- Photos of unknown size --

function setSize(itemData, width, height) {
    itemData.width = width;
    itemData.height = height;
    itemData.w = width;
    itemData.h = height;
}

function withKnownSize(itemData, element, link) {
    // Thumbnails are cropped to fill their box (object-fit: cover), which the
    // opening zoom animation must know to start from the right rectangle.
    itemData.thumbCropped = true;
    if (itemData.width && itemData.height) {
        return itemData;
    }
    const thumbnail = link ? link.querySelector('img') : null;
    if (thumbnail && thumbnail.naturalWidth && thumbnail.naturalHeight) {
        setSize(itemData, thumbnail.naturalWidth, thumbnail.naturalHeight);
    } else {
        // PhotoSwipe won't even start loading a photo without a size: show it
        // viewport-sized for now, and learn the real one when it has loaded.
        setSize(itemData, window.innerWidth, window.innerHeight);
        itemData.sizeUnknown = true;
    }
    return itemData;
}

// -- The viewer --

const lightbox = new PhotoSwipeLightbox({
    gallery: GALLERY_SELECTOR,
    children: ITEM_SELECTOR,
    pswpModule: () => import('/webjars/photoswipe/dist/photoswipe.esm.min.js'),
    mainClass: 'beacon-pswp',
    bgOpacity: 0.94,
    paddingFn,
    counter: false,
    zoom: false,
    closeTitle: 'Close photo',
    arrowPrevTitle: 'Previous photo',
    arrowNextTitle: 'Next photo',
    closeSVG: icon('<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>'),
    arrowPrevSVG: icon('<polyline points="15 18 9 12 15 6"/>'),
    arrowNextSVG: icon('<polyline points="9 18 15 12 9 6"/>'),
    errorMsg: 'This photo could not be loaded.'
});

lightbox.addFilter('domItemData', withKnownSize);

/** Once a photo of unknown size has loaded: remember its size on its link, and lay its slide out again. */
function learnSize(pswp, content) {
    const link = content.data.element;
    const image = content.element;
    if (!content.data.sizeUnknown || !link || !(image instanceof HTMLImageElement) || !image.naturalWidth) {
        return;
    }
    content.data.sizeUnknown = false;
    link.dataset.pswpWidth = String(image.naturalWidth);
    link.dataset.pswpHeight = String(image.naturalHeight);
    // Not synchronously: PhotoSwipe still uses this content after its load event.
    setTimeout(() => {
        if (lightbox.pswp === pswp) {
            pswp.refreshSlideContent(content.index);
        }
    });
}

lightbox.on('loadComplete', ({ content, isError }) => {
    if (!isError) {
        learnSize(lightbox.pswp, content);
    }
});

// The photo opened first may have finished loading before its slide existed.
lightbox.on('change', () => {
    const slide = lightbox.pswp.currSlide;
    if (slide && slide.content.state === 'loaded') {
        learnSize(lightbox.pswp, slide.content);
    }
});

lightbox.on('uiRegister', () => {
    lightbox.pswp.ui.registerElement({
        name: 'caption',
        className: 'photo-viewer-caption',
        appendTo: 'root',
        onInit: (bar, pswp) => {
            bar.setAttribute('aria-live', 'polite');
            pswp.on('change', () => {
                const link = pswp.currSlide ? pswp.currSlide.data.element : null;
                if (link) {
                    fillCaption(bar, link, (pswp.currIndex + 1) + ' / ' + pswp.getNumItems());
                }
            });
        }
    });
});

lightbox.init();
