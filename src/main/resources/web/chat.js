/*
 * The conversation and the composer.
 *
 * Phase two of the move off Swing: the status strip is still native above this page, but
 * everything below it — transcript, edits, input, controls — lives here. The input is the reason.
 * A wrapping Swing text area re-wraps its whole document on every keystroke to answer the
 * viewport's preferred-size query, which is what made typing stutter once the heap was tight.
 *
 * This file owns no conversation state. The plugin decides what is visible and sends it over as
 * HTML or a small JSON event; what happens here is DOM plumbing and key handling.
 */
(function () {
  'use strict';

  var els = {};
  var strings = {};
  var state = {
    atBottom: true,
    running: false,
    sendOnEnter: true,
    commands: [],
    filtered: [],
    selected: 0,
    popupOpen: false,
    composing: false
  };

  function send(msg) {
    if (window.__piSend) window.__piSend(JSON.stringify(msg));
  }

  function t(key) { return strings[key] || ''; }

  function init() {
    ['scroller', 'empty', 'earlier', 'messages', 'streaming', 'edits', 'composer',
     'attachments', 'command-popup', 'input-shell', 'input', 'controls', 'attach',
     'provider', 'model', 'thinking', 'context', 'compact', 'stop', 'send'
    ].forEach(function (id) { els[id] = document.getElementById(id); });

    els.scroller.addEventListener('scroll', function () {
      var el = els.scroller;
      state.atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    });

    // Copy buttons and the "load earlier" row live inside plugin-produced HTML, so they are
    // handled by delegation rather than wired up per message.
    els.scroller.addEventListener('click', function (e) {
      var copy = e.target.closest && e.target.closest('.code-copy');
      if (copy) {
        var wrap = copy.closest('.code-wrap');
        var pre = wrap && wrap.querySelector('pre.code');
        if (pre) send({ type: 'copy', text: pre.textContent });
        return;
      }
      if (e.target.closest && e.target.closest('.load-earlier')) send({ type: 'loadEarlier' });
    });

    els.input.addEventListener('input', onInput);
    els.input.addEventListener('keydown', onKeyDown);
    els.input.addEventListener('paste', onPaste);
    els.input.addEventListener('focus', function () { els['input-shell'].classList.add('focused'); });
    els.input.addEventListener('blur', function () {
      els['input-shell'].classList.remove('focused');
      hidePopup();
    });
    // An IME is mid-word between these two events; Enter must not send there.
    els.input.addEventListener('compositionstart', function () { state.composing = true; });
    els.input.addEventListener('compositionend', function () { state.composing = false; });

    els.send.addEventListener('click', doSend);
    els.stop.addEventListener('click', function () { send({ type: 'abort' }); });
    els.attach.addEventListener('click', function () { send({ type: 'attach' }); });
    els.compact.addEventListener('click', function () { send({ type: 'compact' }); });

    els.provider.addEventListener('change', function () {
      send({ type: 'setProvider', id: els.provider.value });
    });
    els.model.addEventListener('change', function () {
      send({ type: 'setModel', id: els.model.value });
    });
    els.thinking.addEventListener('change', function () {
      send({ type: 'setThinking', level: els.thinking.value });
    });

    document.addEventListener('dragover', function (e) { e.preventDefault(); });
    document.addEventListener('drop', onDrop);

    var buffered = early;
    early = [];
    buffered.forEach(function (e) { window.pi.on(e); });

    send({ type: 'ready' });
  }

  // -------------------------------------------------------------- plugin → JS

  /**
   * Events that arrived before the DOM was ready.
   *
   * The plugin already queues until the page loads, so this should stay empty in practice — but a
   * dropped `theme` or `messages` would leave a blank panel with no way back, and buffering is
   * cheaper than that risk.
   */
  var early = [];

  window.pi = {
    on: function (e) {
      if (!els.scroller) { early.push(e); return; }
      var h = handlers[e.type];
      if (h) h(e);
    },
    // Called by the bridge once `window.__piSend` exists, which is after this script parsed.
    __bridgeReady: function () { send({ type: 'ready' }); }
  };

  var handlers = {
    theme: function (e) {
      var root = document.documentElement;
      Object.keys(e.vars || {}).forEach(function (k) {
        root.style.setProperty('--' + k, e.vars[k]);
      });
      autoGrow();
    },

    i18n: function (e) {
      strings = e.strings || {};
      els.input.placeholder = t('placeholder');
      els.send.title = state.running ? t('queue') : t('send');
      els.stop.title = t('stop');
      els.attach.title = t('attach');
      els.compact.title = t('compact');
      var title = els.empty.querySelector('.empty-title');
      if (title) title.textContent = t('emptyTitle');
    },

    settings: function (e) {
      if (typeof e.sendOnEnter === 'boolean') state.sendOnEnter = e.sendOnEnter;
    },

    empty: function (e) {
      els.empty.hidden = false;
      els.earlier.innerHTML = '';
      els.messages.innerHTML = '';
      els.streaming.innerHTML = '';
      var title = els.empty.querySelector('.empty-title');
      var path = els.empty.querySelector('.empty-path');
      if (title) title.textContent = e.title || t('emptyTitle');
      if (path) path.textContent = e.path || '';
    },

    messages: function (e) {
      els.empty.hidden = true;
      els.earlier.innerHTML = e.earlier || '';
      els.messages.innerHTML = e.html || '';
      els.streaming.innerHTML = '';
      scrollToBottom();
    },

    append: function (e) {
      els.empty.hidden = true;
      appendHtml(els.messages, e.html);
      if (state.atBottom) scrollToBottom();
    },

    prepend: function (e) {
      els.earlier.innerHTML = e.earlier || '';
      var before = els.scroller.scrollHeight;
      var wrap = document.createElement('div');
      wrap.innerHTML = e.html || '';
      var first = els.messages.firstChild;
      while (wrap.lastChild) els.messages.insertBefore(wrap.lastChild, first);
      // Content grew above the viewport; hold the reading position steady.
      if (state.atBottom) scrollToBottom();
      else els.scroller.scrollTop += els.scroller.scrollHeight - before;
    },

    streaming: function (e) {
      if (e.html) els.empty.hidden = true;
      els.streaming.innerHTML = e.html || '';
      if (state.atBottom) scrollToBottom();
    },

    scrollToBottom: function () { scrollToBottom(); },

    running: function (e) {
      state.running = !!e.running;
      els.stop.hidden = !state.running;
      els.compact.disabled = state.running;
      // Sending mid-run steers the turn in progress rather than starting a new one.
      els.send.title = state.running ? t('queue') : t('send');
    },

    models: function (e) {
      fillCombo(els.provider, e.providers, e.provider);
      fillCombo(els.model, e.models, e.model);
      fillCombo(els.thinking, e.thinking, e.thinkingLevel);
    },

    context: function (e) {
      els.context.hidden = !e.label;
      els.context.textContent = e.label || '';
      els.context.title = e.tooltip || '';
    },

    edits: function (e) {
      var items = e.items || [];
      els.edits.hidden = !items.length;
      els.edits.innerHTML = '';
      items.forEach(function (it) {
        var row = document.createElement('div');
        row.className = 'edit-row';
        var p = document.createElement('span');
        p.className = 'edit-path';
        p.textContent = it.path;
        p.title = it.path;
        p.addEventListener('click', function () { send({ type: 'openDiff', path: it.path }); });
        row.appendChild(p);
        if (it.added) {
          var a = document.createElement('span');
          a.className = 'edit-add';
          a.textContent = '+' + it.added;
          row.appendChild(a);
        }
        if (it.removed) {
          var d = document.createElement('span');
          d.className = 'edit-del';
          d.textContent = '−' + it.removed;
          row.appendChild(d);
        }
        els.edits.appendChild(row);
      });
    },

    attachments: function (e) {
      var items = e.items || [];
      els.attachments.hidden = !items.length;
      els.attachments.innerHTML = '';
      items.forEach(function (it) {
        var chip = document.createElement('span');
        chip.className = 'chip';
        var label = document.createElement('span');
        label.textContent = it.name;
        label.title = it.name;
        var x = document.createElement('button');
        x.textContent = '×';
        x.addEventListener('click', function () {
          send({ type: 'removeAttachment', id: it.id });
        });
        chip.appendChild(label);
        chip.appendChild(x);
        els.attachments.appendChild(chip);
      });
    },

    commands: function (e) {
      state.commands = e.items || [];
      if (state.popupOpen) refreshPopup();
    },

    /** Composer text set by the plugin: @mention insertion, and clearing after a send. */
    composer: function (e) {
      els.input.value = e.text || '';
      autoGrow();
      hidePopup();
      if (e.focus) els.input.focus();
    },

    /** "Send path to Pi GUI" drops an @mention in without disturbing what is already typed. */
    appendComposer: function (e) {
      var current = els.input.value;
      var sep = (current && !/\s$/.test(current)) ? ' ' : '';
      els.input.value = current + sep + (e.text || '') + ' ';
      els.input.selectionStart = els.input.selectionEnd = els.input.value.length;
      autoGrow();
      els.input.focus();
    },

    focusInput: function () { els.input.focus(); }
  };

  function fillCombo(el, items, selected) {
    el.innerHTML = '';
    (items || []).forEach(function (it) {
      var opt = document.createElement('option');
      opt.value = it.id !== undefined ? it.id : it;
      opt.textContent = it.label !== undefined ? it.label : it;
      el.appendChild(opt);
    });
    el.disabled = !(items && items.length);
    if (selected !== undefined && selected !== null && selected !== '') el.value = selected;
  }

  function appendHtml(parent, html) {
    var wrap = document.createElement('div');
    wrap.innerHTML = html || '';
    while (wrap.firstChild) parent.appendChild(wrap.firstChild);
  }

  function scrollToBottom() {
    els.scroller.scrollTop = els.scroller.scrollHeight;
    state.atBottom = true;
  }

  // ------------------------------------------------------------------- input

  function autoGrow() {
    els.input.style.height = 'auto';
    els.input.style.height = Math.min(els.input.scrollHeight, 180) + 'px';
  }

  function onInput() {
    autoGrow();
    updatePopup();
  }

  function doSend() {
    var text = els.input.value.trim();
    if (!text && els.attachments.hidden) return;
    hidePopup();
    send({ type: 'send', text: text });
  }

  function onKeyDown(e) {
    if (state.popupOpen) {
      if (e.key === 'ArrowDown') { move(1); e.preventDefault(); return; }
      if (e.key === 'ArrowUp') { move(-1); e.preventDefault(); return; }
      if (e.key === 'Escape') { hidePopup(); e.preventDefault(); return; }
      if ((e.key === 'Enter' || e.key === 'Tab') && !e.isComposing && !state.composing) {
        if (accept()) { e.preventDefault(); return; }
      }
    }
    if (e.key !== 'Enter' || e.isComposing || state.composing) return;
    // Enter sends and Shift+Enter breaks the line, or the reverse, per the setting.
    var wantsSend = e.shiftKey ? !state.sendOnEnter : state.sendOnEnter;
    if (wantsSend) {
      doSend();
      e.preventDefault();
    }
  }

  function onPaste(e) {
    var items = (e.clipboardData && e.clipboardData.items) || [];
    for (var i = 0; i < items.length; i++) {
      if (items[i].kind === 'file') {
        // The plugin reads the real clipboard: it knows the paths and the size limits.
        send({ type: 'pasteClipboard' });
        e.preventDefault();
        return;
      }
    }
  }

  function onDrop(e) {
    e.preventDefault();
    var paths = droppedPaths(e.dataTransfer);
    if (paths.length) send({ type: 'dropFiles', paths: paths });
  }

  /**
   * File paths out of a drop.
   *
   * `File.path` is an Electron extension that plain Chromium does not provide, so the standard
   * `text/uri-list` is what actually carries OS file drops here; both are tried.
   */
  function droppedPaths(dt) {
    var paths = [];
    var files = (dt && dt.files) || [];
    for (var i = 0; i < files.length; i++) if (files[i].path) paths.push(files[i].path);
    if (paths.length || !dt) return paths;

    var list = '';
    try { list = dt.getData('text/uri-list') || ''; } catch (err) { list = ''; }
    list.split(/\r?\n/).forEach(function (line) {
      if (!line || line.charAt(0) === '#' || line.indexOf('file://') !== 0) return;
      var path;
      try { path = decodeURIComponent(line.slice(7)); } catch (err) { return; }
      // A Windows URI is file:///C:/… — the leading slash is part of the URI, not the path.
      if (/^\/[A-Za-z]:/.test(path)) path = path.slice(1);
      paths.push(path);
    });
    return paths;
  }

  // ------------------------------------------------------------ command popup

  /** pi only treats `/name` as a command at the very start of the message. */
  function commandQuery() {
    var v = els.input.value;
    if (v.charAt(0) !== '/') return null;
    var caret = els.input.selectionStart;
    var token = v.slice(1).split(/\s/)[0];
    if (caret > token.length + 1) return null;
    return token.slice(0, Math.max(0, caret - 1));
  }

  function updatePopup() {
    var q = commandQuery();
    if (q === null) { hidePopup(); return; }
    if (!state.popupOpen) send({ type: 'commands' });
    state.popupOpen = true;
    refreshPopup(q);
  }

  function rank(name, needle) {
    if (!needle) return 0;
    if (name.indexOf(needle) === 0) return 0;
    if (name.slice(name.indexOf(':') + 1).indexOf(needle) === 0) return 1;
    return 2;
  }

  function refreshPopup(query) {
    var q = query !== undefined ? query : commandQuery();
    if (q === null) { hidePopup(); return; }
    var needle = q.toLowerCase();

    state.filtered = state.commands.filter(function (c) {
      var n = c.name.toLowerCase();
      return !needle || n.indexOf(needle) >= 0 ||
        n.slice(n.indexOf(':') + 1).indexOf(needle) === 0;
    }).sort(function (a, b) {
      return rank(a.name.toLowerCase(), needle) - rank(b.name.toLowerCase(), needle);
    });

    if (state.selected >= state.filtered.length) state.selected = 0;

    var box = els['command-popup'];
    box.innerHTML = '';
    box.hidden = false;

    if (!state.filtered.length) {
      var none = document.createElement('div');
      none.className = 'cmd-empty';
      none.textContent = t('noCommand');
      box.appendChild(none);
      return;
    }
    state.filtered.forEach(function (c, i) {
      var row = document.createElement('div');
      row.className = 'cmd' + (i === state.selected ? ' sel' : '');
      var n = document.createElement('span');
      n.className = 'cmd-name';
      n.textContent = '/' + c.name;
      var d = document.createElement('span');
      d.className = 'cmd-desc';
      d.textContent = c.description || '';
      row.appendChild(n);
      row.appendChild(d);
      // mousedown, not click: the input must not lose focus before the insert lands.
      row.addEventListener('mousedown', function (ev) {
        ev.preventDefault();
        state.selected = i;
        accept();
      });
      box.appendChild(row);
    });
  }

  function move(delta) {
    if (!state.filtered.length) return;
    state.selected = Math.max(0, Math.min(state.filtered.length - 1, state.selected + delta));
    refreshPopup();
    var sel = els['command-popup'].querySelector('.cmd.sel');
    if (sel && sel.scrollIntoView) sel.scrollIntoView({ block: 'nearest' });
  }

  function accept() {
    var cmd = state.filtered[state.selected];
    if (!cmd) return false;
    var v = els.input.value;
    var token = v.slice(1).split(/\s/)[0];
    var rest = v.slice(1 + token.length).replace(/^\s+/, '');
    els.input.value = '/' + cmd.name + ' ' + rest;
    var caret = cmd.name.length + 2;
    els.input.selectionStart = els.input.selectionEnd = caret;
    hidePopup();
    autoGrow();
    return true;
  }

  function hidePopup() {
    state.popupOpen = false;
    state.selected = 0;
    els['command-popup'].hidden = true;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
