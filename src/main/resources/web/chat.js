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
    composing: false,
    models: { providers: [], provider: null, models: [], model: null, thinking: [], thinkingLevel: null },
    context: { label: '', tooltip: '' }
  };

  /*
   * Inline, stroked, sized in em and drawn in currentColor, so they follow the IDE's font size
   * and the pill they sit in — including the white-on-blue send button — with no second asset.
   */
  var ICON = {
    attach: '<path d="M14.5 6.5 8 13a3 3 0 0 1-4.2-4.2l6.6-6.6a2 2 0 0 1 2.9 2.9l-6.6 6.6a1 1 0 0 1-1.4-1.4L11.6 4"/>',
    provider: '<rect x="3" y="6" width="12" height="8" rx="2"/><path d="M9 6V3.5M6.5 10h.01M11.5 10h.01"/>',
    model: '<rect x="3" y="4" width="12" height="11" rx="2"/><path d="M6 8h6M6 11h4"/>',
    thinking: '<path d="M8 3.5A2.5 2.5 0 0 0 5.5 6v6A2.5 2.5 0 0 0 8 14.5ZM10 3.5A2.5 2.5 0 0 1 12.5 6v6a2.5 2.5 0 0 1-2.5 2.5ZM8 3.5h2M8 14.5h2"/>',
    compact: '<rect x="2.5" y="2.5" width="13" height="13" rx="3"/><circle cx="9" cy="9" r="3"/>',
    send: '<path d="M15 3 8 10M15 3l-4.5 12L8 10 3 7.5Z"/>',
    stop: '<rect x="5" y="5" width="8" height="8" rx="1.5"/>',
    tick: '<path d="M3.5 9l3 3 6-7"/>'
  };

  function icon(name, cls) {
    return '<svg class="' + (cls || 'glyph-icon') + '" viewBox="0 0 18 18" width="1em" height="1em" ' +
      'fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" ' +
      'stroke-linejoin="round" aria-hidden="true">' + ICON[name] + '</svg>';
  }

  var CHEVRON = '<svg class="chevron" viewBox="0 0 18 18" width="1em" height="1em" fill="none" ' +
    'stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" ' +
    'aria-hidden="true"><path d="M5 7.5 9 11.5l4-4"/></svg>';

  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function send(msg) {
    if (window.__piSend) window.__piSend(JSON.stringify(msg));
  }

  function t(key) { return strings[key] || ''; }

  function init() {
    ['scroller', 'empty', 'earlier', 'messages', 'streaming', 'edits', 'composer',
     'attachments', 'command-popup', 'composer-card', 'input', 'controls', 'attach',
     'provider', 'model', 'thinking', 'compact', 'stop', 'send', 'menu'
    ].forEach(function (id) { els[id] = document.getElementById(id); });

    paintPills();

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
    els.input.addEventListener('focus', function () { els['composer-card'].classList.add('focused'); });
    els.input.addEventListener('blur', function () {
      els['composer-card'].classList.remove('focused');
      hidePopup();
    });
    // An IME is mid-word between these two events; Enter must not send there.
    els.input.addEventListener('compositionstart', function () { state.composing = true; });
    els.input.addEventListener('compositionend', function () { state.composing = false; });

    els.send.addEventListener('click', doSend);
    els.stop.addEventListener('click', function () { send({ type: 'abort' }); });
    els.attach.addEventListener('click', function () { send({ type: 'attach' }); });

    els.provider.addEventListener('click', function () {
      openMenu(els.provider, state.models.providers, state.models.provider, function (id) {
        send({ type: 'setProvider', id: id });
      });
    });
    els.model.addEventListener('click', function () {
      openMenu(els.model, state.models.models, state.models.model, function (id) {
        send({ type: 'setModel', id: id });
      });
    });
    els.thinking.addEventListener('click', function () {
      openMenu(els.thinking, state.models.thinking, state.models.thinkingLevel, function (id) {
        send({ type: 'setThinking', level: id });
      });
    });
    // pi compacts on its own when the context fills up, so the only choice to offer is doing it
    // now; the readout above it says how full the window currently is.
    els.compact.addEventListener('click', function () {
      openMenu(
        els.compact,
        [{ id: 'now', label: t('compactNow') }],
        null,
        function () { send({ type: 'compact' }); },
        state.context.label ? t('contextPrefix') + ' ' + state.context.label : ''
      );
    });

    // A click anywhere else closes the dropdown; the pill's own handler reopens it.
    document.addEventListener('mousedown', function (e) {
      if (els.menu.hidden) return;
      if (els.menu.contains(e.target)) return;
      if (e.target.closest && e.target.closest('.pill.menu')) return;
      hideMenu();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !els.menu.hidden) { hideMenu(); e.preventDefault(); }
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
      paintPills();
      paintEmpty();
    },

    settings: function (e) {
      if (typeof e.sendOnEnter === 'boolean') state.sendOnEnter = e.sendOnEnter;
    },

    empty: function (e) {
      els.empty.hidden = false;
      els.earlier.innerHTML = '';
      els.messages.innerHTML = '';
      els.streaming.innerHTML = '';
      state.emptyPath = e.path || '';
      paintEmpty(e.title);
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
      // Sending mid-run steers the turn in progress rather than starting a new one, so the send
      // button stays live and only its wording changes.
      paintPills();
    },

    models: function (e) {
      state.models = {
        providers: e.providers || [],
        provider: e.provider === undefined ? null : e.provider,
        models: e.models || [],
        model: e.model === undefined ? null : e.model,
        thinking: e.thinking || [],
        thinkingLevel: e.thinkingLevel === undefined ? null : e.thinkingLevel
      };
      hideMenu();
      paintPills();
    },

    context: function (e) {
      state.context = { label: e.label || '', tooltip: e.tooltip || '' };
      paintPills();
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

  // ------------------------------------------------------------------- pills

  /**
   * Redraw every control from [state]. Cheap enough to do wholesale: five buttons, and it only
   * runs when the plugin pushes a change, never while typing.
   */
  function paintPills() {
    pill(els.attach, 'attach', null, t('attach'), false, false);
    pill(els.send, 'send', null, t(state.running ? 'queue' : 'send'), false, false);
    pill(els.stop, 'stop', null, t('stop'), false, false);

    var m = state.models;
    pill(els.provider, 'provider', null, labelOf(m.providers, m.provider) || t('provider'),
         true, !m.providers.length);
    pill(els.model, 'model', null, labelOf(m.models, m.model) || t('modelNone'),
         true, !m.models.length);
    pill(els.thinking, 'thinking', t('thinkingPrefix'),
         labelOf(m.thinking, m.thinkingLevel) || t('thinkingNone'), true, !m.thinking.length);
    // pi decides when to compact on its own; the readout of how full the window is lives in the
    // dropdown, where it does not make the control jump about as the number changes.
    pill(els.compact, 'compact', t('compactPrefix'), t('compactAuto'), true, state.running);
    els.compact.title = state.context.tooltip || '';
  }

  function pill(el, glyph, prefix, label, dropdown, disabled) {
    el.innerHTML = icon(glyph) +
      (prefix ? '<span class="prefix">' + escapeHtml(prefix) + '</span>' : '') +
      '<span class="label">' + escapeHtml(label) + '</span>' +
      (dropdown ? CHEVRON : '');
    el.disabled = !!disabled;
  }

  function labelOf(items, id) {
    if (id === null || id === undefined) return '';
    for (var i = 0; i < items.length; i++) if (items[i].id === id) return items[i].label;
    return id;
  }

  // ---------------------------------------------------------- pill dropdown

  /**
   * Open the shared dropdown above [anchor].
   *
   * Above, not below: the composer sits on the bottom edge, so a menu hanging downwards would
   * fall off the panel. One element is reused for all four pills — only one can be open.
   */
  function openMenu(anchor, items, selected, pick, note) {
    if (!items || !items.length) { hideMenu(); return; }
    if (els.menu.dataset.owner === anchor.id && !els.menu.hidden) { hideMenu(); return; }

    els.menu.innerHTML = '';
    els.menu.dataset.owner = anchor.id;

    if (note) {
      var head = document.createElement('div');
      head.className = 'menu-note';
      head.textContent = note;
      els.menu.appendChild(head);
      els.menu.appendChild(Object.assign(document.createElement('div'), { className: 'menu-separator' }));
    }

    items.forEach(function (it) {
      var row = document.createElement('div');
      var chosen = selected !== null && selected !== undefined && it.id === selected;
      row.className = 'menu-item' + (chosen ? ' selected' : '');
      row.innerHTML = (chosen ? icon('tick', 'tick') : '<span class="tick"></span>') +
        '<span class="menu-label">' + escapeHtml(it.label) + '</span>';
      row.addEventListener('mousedown', function (ev) {
        ev.preventDefault();
        hideMenu();
        pick(it.id);
      });
      els.menu.appendChild(row);
    });

    // Measure before placing: the menu has to know its own height to sit on top of the pill.
    els.menu.hidden = false;
    els.menu.style.left = '0px';
    els.menu.style.bottom = '0px';
    var host = els.composer.getBoundingClientRect();
    var box = anchor.getBoundingClientRect();
    var width = els.menu.offsetWidth;
    var left = Math.min(Math.max(box.left - host.left, 8), Math.max(host.width - width - 8, 8));
    els.menu.style.left = left + 'px';
    els.menu.style.bottom = (host.bottom - box.top + 6) + 'px';
  }

  function hideMenu() {
    els.menu.hidden = true;
    els.menu.dataset.owner = '';
  }

  function paintEmpty(title) {
    var h1 = els.empty.querySelector('.empty-title');
    var sub = els.empty.querySelector('.empty-subtitle');
    if (h1) {
      h1.textContent = title || state.emptyTitle || t('emptyTitle');
      // The project is what the greeting is about, but naming it competes with the greeting.
      h1.title = state.emptyPath || '';
      state.emptyTitle = h1.textContent;
    }
    if (sub) sub.textContent = t('emptySubtitle');
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
    els.input.style.height = Math.min(Math.max(els.input.scrollHeight, 42), 180) + 'px';
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
