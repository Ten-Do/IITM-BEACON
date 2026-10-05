/*
 * Alpine.js components for the submission form (submission/form.html).
 *
 * Loaded with `defer` BEFORE the Alpine script itself, so the components are
 * registered on `alpine:init` — i.e. before Alpine walks the DOM and meets
 * the x-data attributes that reference them. Server values reach the
 * components only through data-* attributes; the page stays fully usable
 * without JS (every topic block is then shown and submitted).
 */
document.addEventListener('alpine:init', () => {
  /* global Alpine */

  /*
   * Whole form: right before submitting, disables every photo input that
   * holds no file, so it is not sent as an (empty) multipart part at all. If
   * the page is later restored from the back/forward cache, those inputs are
   * enabled again.
   *
   * Only inputs that were enabled at submit time are recorded and restored,
   * so this never re-enables an input the topicBlock binding keeps disabled
   * (a topic without text). The Alpine state that binding reads is restored
   * from the cache together with the page, so the re-enabled inputs match it.
   */
  Alpine.data('submissionForm', () => {
    const disabledOnSubmit = [];

    return {
      disableEmptyUploads() {
        this.$el.querySelectorAll('input[type="file"]').forEach((input) => {
          if (!input.disabled && input.files.length === 0) {
            input.disabled = true;
            disabledOnSubmit.push(input);
          }
        });
      },

      restoreUploads() {
        disabledOnSubmit.splice(0).forEach((input) => {
          input.disabled = false;
        });
      },
    };
  });

  /*
   * Topic picker: one toggle chip per topic group / standalone topic. A
   * picked key reveals its fieldset; an unpicked one is hidden and disabled
   * (so its fields are not submitted). data-initial-picked is a
   * space-separated list of keys the server decided to open with.
   */
  Alpine.data('topicPicker', () => ({
    picked: [],

    init() {
      this.picked = (this.$el.dataset.initialPicked || '')
        .split(' ')
        .filter((key) => key !== '');
    },

    isPicked(key) {
      return this.picked.includes(key);
    },

    toggle(key) {
      this.picked = this.isPicked(key)
        ? this.picked.filter((picked) => picked !== key)
        : [...this.picked, key];
    },
  }));

  const isBlank = (value) => value.trim() === '';

  /*
   * One topic block (answer textarea + its photos): the photo input — and
   * the new photos' tags fields — stay disabled while the answer is blank,
   * since photos on a topic without text are rejected (UC-CREATE-TESTIMONIAL).
   * The textarea's server-rendered value decides the starting state, so a
   * pre-filled (edit-mode or re-rendered) answer enables them. Elements are
   * looked up from the component root ($el): init() runs before child x-refs
   * exist.
   */
  Alpine.data('topicBlock', () => ({
    hasText: false,

    init() {
      this.hasText = !isBlank(this.$el.querySelector('textarea').value);
    },

    refresh(event) {
      this.hasText = !isBlank(event.target.value);
    },
  }));

  const photoCount = (count) => `${count} ${count === 1 ? 'photo' : 'photos'}`;

  const sameFile = (a, b) => a.name === b.name && a.size === b.size && a.lastModified === b.lastModified;

  /* Setting input.files needs a DataTransfer; very old browsers can't build one. */
  const canRebuildFileLists = (() => {
    try {
      return typeof new DataTransfer().items.add === 'function';
    } catch (e) {
      return false;
    }
  })();

  /*
   * The photos of one topic block: the saved ones (edit mode, rendered by the
   * server) and the new ones chosen here — previewed in the browser, each
   * with a remove (×) button and its own tags field. They are uploaded with
   * the form, from the block's one multiple file input: its file list is
   * rebuilt from newPhotos after every add or remove, so the file at index j
   * is always submitted with the tags field photoTags[j].
   *
   * Choosing (or dropping) files adds to what is already there. A file is
   * refused, with a message, when it is larger than the per-file limit, not
   * an image, or over the per-topic or whole-testimonial photo count — saved
   * photos included. The limits come from the data-* attributes the server
   * renders from its own configuration. Removing a saved photo ticks its
   * (hidden) Remove checkbox, the same thing a browser without JS submits.
   */
  Alpine.data('photoPicker', () => {
    // The component's own element. Not this.$el: in a method called from a
    // child's directive (a remove button, the file input) that is the child.
    let root = null;

    return {
      newPhotos: [],
      removedUrls: [],
      message: '',
      dragging: false,
      nextId: 0,

      init() {
        root = this.$el;
        root.classList.add('submission-photos--js');
        this.removedUrls = this.savedCheckboxes().filter((box) => box.checked).map((box) => box.value);
        // A browser may restore a file selection (Back, reload); show it.
        this.sync();
        this.warnIfOverLimit();
      },

      // -- limits (from the server) --

      maxPerTopic() {
        return Number(root.dataset.maxPerTopic);
      },

      maxTotal() {
        return Number(root.dataset.maxTotal);
      },

      maxBytes() {
        return Number(root.dataset.maxBytes);
      },

      tagsName(index) {
        return `${root.dataset.tagsName}[${index}]`;
      },

      // -- counts --

      input() {
        return root.querySelector('input[type="file"]');
      },

      savedCheckboxes() {
        return [...root.querySelectorAll('[data-saved-photo] input[type="checkbox"]')];
      },

      isRemoved(url) {
        return this.removedUrls.includes(url);
      },

      count() {
        return this.savedCheckboxes().length - this.removedUrls.length + this.newPhotos.length;
      },

      countLabel() {
        const count = this.count();
        return count === 0 ? '' : `${count} of ${this.maxPerTopic()}`;
      },

      /* Photos of the whole form: the other topics' as their inputs hold them, plus this one's. */
      formTotal() {
        const form = root.closest('form') || document;
        let total = this.count();
        form.querySelectorAll('[data-photo-picker]').forEach((picker) => {
          if (picker === root) {
            return;
          }
          const saved = picker.querySelectorAll('[data-saved-photo] input[type="checkbox"]:not(:checked)');
          const input = picker.querySelector('input[type="file"]');
          total += saved.length + (input ? input.files.length : 0);
        });
        return total;
      },

      // -- adding --

      choose(event) {
        this.add(event.target.files);
      },

      add(fileList) {
        const chosen = [...fileList];
        if (!canRebuildFileLists) {
          this.replaceWith(chosen);
          return;
        }
        this.message = this.accept(chosen).join(' ');
        this.writeInput();
      },

      /* Appends every acceptable file to newPhotos; returns the messages for the others. */
      accept(files) {
        const messages = [];
        let topicFull = false;
        let formFull = false;
        files.forEach((file) => {
          if (file.type && !file.type.startsWith('image/')) {
            messages.push(`${file.name} is not an image.`);
          } else if (file.size > this.maxBytes()) {
            messages.push(`${file.name} is larger than ${root.dataset.maxSizeLabel}.`);
          } else if (this.count() >= this.maxPerTopic()) {
            topicFull = true;
          } else if (this.formTotal() >= this.maxTotal()) {
            formFull = true;
          } else {
            this.newPhotos.push(this.photoFor(file));
          }
        });
        if (topicFull) {
          messages.push(`You can add at most ${photoCount(this.maxPerTopic())} to this topic.`);
        }
        if (formFull) {
          messages.push(`You can add at most ${photoCount(this.maxTotal())} in total.`);
        }
        return messages;
      },

      photoFor(file) {
        this.nextId += 1;
        return {
          id: this.nextId,
          file,
          name: file.name,
          url: URL.createObjectURL(file),
          previewable: true,
          tags: '',
        };
      },

      /* Without DataTransfer the input can only hold the latest selection: all of it, or none. */
      replaceWith(chosen) {
        this.clearNewPhotos();
        const messages = this.accept(chosen);
        if (messages.length > 0) {
          this.clearNewPhotos();
          this.input().value = '';
        }
        this.message = messages.join(' ');
      },

      writeInput() {
        const transfer = new DataTransfer();
        this.newPhotos.forEach((photo) => transfer.items.add(Alpine.raw(photo).file));
        this.input().files = transfer.files;
      },

      // -- removing --

      removeNew(index) {
        if (!canRebuildFileLists) {
          this.clearNewPhotos();
          this.input().value = '';
        } else {
          const [removed] = this.newPhotos.splice(index, 1);
          URL.revokeObjectURL(removed.url);
          this.writeInput();
        }
        this.message = '';
        this.warnIfOverLimit();
      },

      removeSaved(url) {
        const box = this.savedCheckboxes().find((each) => each.value === url);
        if (box) {
          box.checked = true;
        }
        if (!this.isRemoved(url)) {
          this.removedUrls.push(url);
        }
        this.message = '';
        this.warnIfOverLimit();
      },

      clearNewPhotos() {
        this.newPhotos.splice(0).forEach((photo) => URL.revokeObjectURL(photo.url));
      },

      // -- keeping the previews in step with the input --

      /*
       * Rebuilds newPhotos from the input when the two disagree: a selection
       * the browser restored on load, or after Back/Forward. A page restored
       * from the back/forward cache keeps both as they were, so nothing
       * changes (and the typed tags stay). Files are compared by name, size
       * and date, not identity: a browser may hand back new File objects.
       */
      sync() {
        const files = [...this.input().files];
        const same = files.length === this.newPhotos.length
          && files.every((file, i) => sameFile(file, Alpine.raw(this.newPhotos[i]).file));
        if (same) {
          return;
        }
        this.clearNewPhotos();
        if (files.length > 0) {
          this.add(files);
        }
      },

      /* Saved under an older, higher limit: say so, since the server will refuse it. */
      warnIfOverLimit() {
        const excess = this.count() - this.maxPerTopic();
        if (excess > 0) {
          this.message = `At most ${photoCount(this.maxPerTopic())} per topic — please remove ${excess}.`;
        }
      },
    };
  });

  /*
   * One contact row: "Show publicly" is disabled — and unchecked — while the
   * row's value is blank, since a public contact needs a value. A pre-filled
   * row (edit mode) starts enabled with its saved choice.
   */
  Alpine.data('contactRow', () => ({
    hasValue: false,

    init() {
      this.update(this.$el.querySelector('input[type="text"]').value);
    },

    refresh(event) {
      this.update(event.target.value);
    },

    update(value) {
      this.hasValue = !isBlank(value);
      if (!this.hasValue) {
        this.$el.querySelector('input[type="checkbox"]').checked = false;
      }
    },
  }));

  /*
   * Recommendation score slider: keeps the number, its colour and the one
   * visible label in step with the range input (x-model.number="score").
   * data-initial-score is the value the server already rendered; the
   * fallback below matches the server's default for a form without a score.
   */
  Alpine.data('scoreSlider', () => ({
    score: 10,

    init() {
      const initial = Number(this.$el.dataset.initialScore);
      if (Number.isInteger(initial)) {
        this.score = initial;
      }
    },
  }));
});
