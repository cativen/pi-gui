/*
 * The view half of Pi GUI.
 *
 * It owns no state the plugin cares about: every decision — what a message looks like, which
 * commands exist, whether a session can be compacted — is made in Kotlin and arrives here as
 * either HTML or a small JSON event. This file's whole job is DOM plumbing and input handling,
 * which is exactly the part that was expensive in Swing.
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var els = {};
  var strings = {};
  var state = {
    running: false,
    commands: [],
    filtered: [],
    selected: 0,
    popupOpen: false,
    sendOnEnter: true,
    atBottom: true
  };

  /** JS → plugin. `__piSend` is injected by the Kotlin side before the page loads. */
  function send(msg) {
    if (window.__piSend) window.__piSend(JSON.stringify(msg));
  }

  function t(key, fallback) { return strings[key] || fallback || ''; }

  // ------------------------------------------------------------------ startup

  function init() {
    ['sidebar', 'sessions', 'statusbar', 'status', 'branch', 'transcript', 'empty',
     'messages', 'streaming', 'edits', 'composer', 'attachments', 'command-popup',
     'input-shell', 'input', 'controls', 'attach', 'provider', 'model', 'thinking',
     'context', 'compact', 'stop', 'send'].forEach(function (id) {
      els[id] = $(id);
    });

    els.input.addEventListener('keydown', onKeyDown);
    els.input.addEventListener('input', onInput);
    els.input.addEventListener('focus', function () { els['input-shell'].classList.add('focused'); });
    els.input.addEventListener('blur', function () {
      els['input-shell'].classList.remove('focused');
      hidePopup();
    });
    els.input.addEventListener('paste', onPaste);

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

    els.transcript.addEventListener('scroll', function () {
      var el = els.transcript;
      state.atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    });

    // Copy buttons and "load earlier" live inside plugin-produced HTML, so they are handled by
    // delegation rather than wired up per message.
    els.messages.addEventListener('click', onTranscriptClick);
    els.streaming.addEventListener('click', onTranscriptClick);

    // Dropping files anywhere on the view attaches them.
    document.addEventListener('dragover', function (e) { e.preventDefault(); });
    document.addEventListener('drop', onDrop);

    send({ type: 'ready' });
  }

  // -------------------------------------------------------------- plugin → JS

  window.pi = {
    on: function (event) {
      var handler = handlers[event.type];
      if (handler) handler(event);
    }
  };

  var handlers = {
    theme: function (e) {
      var root = document.documentElement;
      Object.keys(e.vars).forEach(function (k) { root.style.setProperty('--' + k, e.vars[k]); });
    },

    i18n: function (e) {
      strings = e.strings || {};
      els.input.placeholder = t('placeholder');
      els.send.title = t('send');
      els.stop.title = t('stop');
      els.attach.title = t('attach');
      els.compact.title = t('compact');
      var title = els.empty.querySelector('.empty-title');
      if (title) title.textContent = t('emptyTitle');
    },

    project: function (e) {
      var path = els.empty.querySelector('.empty-path');
      if (path) path.textContent = e.path || '';
    },

    sessions: function (e) {
      els.sessions.innerHTML = '';
      if (!e.items.length) {
        var none = document.createElement('div');
        none.className = 'sessions-empty';
        none.textContent = t('noSessions');
        els.sessions.appendChild(none);
        return;
      }
      e.items.forEach(function (s) {
        var row = document.createElement('div');
        row.className = 'session' + (s.active ? ' active' : '');
        row.title = s.title;
        var name = document.createElement('span');
        name.textContent = s.title;
        var when = document.createElement('span');
        when.className = 'when';
        when.textContent = s.when || '';
        row.appendChild(name);
        row.appendChild(when);
        row.addEventListener('click', function () { send({ type: 'selectSession', path: s.path }); });
        els.sessions.appendChild(row);
      });
    },

    sidebar: function (e) { els.sidebar.hidden = !e.visible; },

    /** Whole transcript replaced — a session switch. */
    transcript: function (e) {
      els.messages.innerHTML = e.html || '';
      els.streaming.innerHTML = '';
      els.empty.hidden = !!e.html;
      scrollToBottom();
    },

    /** One finished message appended. */
    append: function (e) {
      els.empty.hidden = true;
      var wrap = document.createElement('div');
      wrap.innerHTML = e.html;
      while (wrap.firstChild) els.messages.appendChild(wrap.firstChild);
      if (state.atBottom) scrollToBottom();
    },

    /** Older messages prepended by the chunked backfill. */
    prepend: function (e) {
      var wrap = document.createElement('div');
      wrap.innerHTML = e.html;
      var first = els.messages.firstChild;
      var before = els.transcript.scrollHeight;
      while (wrap.lastChild) els.messages.insertBefore(wrap.lastChild, first);
      // Keep the reading position: content grew above the viewport.
      if (state.atBottom) scrollToBottom();
      else els.transcript.scrollTop += els.transcript.scrollHeight - before;
    },

    /** The in-progress reply; replaced wholesale on each flush. */
    streaming: function (e) {
      els.empty.hidden = true;
      els.streaming.innerHTML = e.html || '';
      if (state.atBottom) scrollToBottom();
    },

    status: function (e) {
      els.status.textContent = e.text || '';
      els.branch.hidden = !e.branch;
      els.branch.textContent = e.branch || '';
      setRunning(!!e.running);
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
      els.edits.hidden = !e.items.length;
      els.edits.innerHTML = '';
      e.items.forEach(function (it) {
        var row = document.createElement('div');
        row.className = 'edit-row';
        var p = document.createElement('span');
        p.className = 'edit-path';
        p.textContent = it.path;
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
      els.attachments.hidden = !e.items.length;
      els.attachments.innerHTML = '';
      e.items.forEach(function (it) {
        var chip = document.createElement('span');
        chip.className = 'chip';
        var label = document.createElement('span');
        label.textContent = it.name;
        var x = document.createElement('button');
        x.textContent = '×';
        x.addEventListener('click', function () { send({ type: 'removeAttachment', id: it.id }); });
        chip.appendChild(label);
        chip.appendChild(x);
        els.attachments.appendChild(chip);
      });
    },

    commands: function (e) {
      state.commands = e.items || [];
      if (state.popupOpen) refreshPopup();
    },

    /** Composer text set from the plugin: @mention insertion, clearing after send. */
    composer: function (e) {
      els.input.value = e.text || '';
      autoGrow();
      hidePopup();
      if (e.focus) els.input.focus();
    },

    settings: function (e) {
      if (typeof e.sendOnEnter === 'boolean') state.sendOnEnter = e.sendOnEnter;
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
    if (selected !== undefined && selected !== null) el.value = selected;
  }

  function setRunning(running) {
    state.running = running;
    els.stop.hidden = !running;
    els.compact.disabled = running;
  }

  function scrollToBottom() {
    // One assignment, no animation: the Swing build learned the hard way that an interpolated
    // scroll repaints every frame of the way down.
    els.transcript.scrollTop = els.transcript.scrollHeight;
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
    if (!text && !els.attachments.children.length) return;
    hidePopup();
    send({ type: 'send', text: text });
  }

  function onKeyDown(e) {
    if (state.popupOpen) {
      if (e.key === 'ArrowDown') { move(1); e.preventDefault(); return; }
      if (e.key === 'ArrowUp') { move(-1); e.preventDefault(); return; }
      if (e.key === 'Escape') { hidePopup(); e.preventDefault(); return; }
      if (e.key === 'Enter' || e.key === 'Tab') {
        if (accept()) { e.preventDefault(); return; }
      }
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      if (state.sendOnEnter) { doSend(); e.preventDefault(); }
    } else if (e.key === 'Enter' && e.shiftKey && !state.sendOnEnter) {
      doSend();
      e.preventDefault();
    }
  }

  function onPaste(e) {
    var items = (e.clipboardData && e.clipboardData.items) || [];
    for (var i = 0; i < items.length; i++) {
      if (items[i].kind === 'file') {
        // Let the plugin read the real clipboard: it has the file path and the size limits.
        send({ type: 'pasteClipboard' });
        e.preventDefault();
        return;
      }
    }
  }

  function onDrop(e) {
    e.preventDefault();
    var files = (e.dataTransfer && e.dataTransfer.files) || [];
    var paths = [];
    for (var i = 0; i < files.length; i++) {
      if (files[i].path) paths.push(files[i].path);
    }
    if (paths.length) send({ type: 'dropFiles', paths: paths });
  }

  function onTranscriptClick(e) {
    var copy = e.target.closest ? e.target.closest('.code-copy') : null;
    if (copy) {
      var pre = copy.parentNode.parentNode.querySelector('pre.code');
      if (pre) send({ type: 'copy', text: pre.textContent });
      return;
    }
    var earlier = e.target.closest ? e.target.closest('.load-earlier') : null;
    if (earlier) send({ type: 'loadEarlier' });
  }

  // ---------------------------------------------------------- command popup

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

  function refreshPopup(query) {
    var q = query !== undefined ? query : commandQuery();
    if (q === null) { hidePopup(); return; }
    var needle = q.toLowerCase();
    state.filtered = state.commands.filter(function (c) {
      var n = c.name.toLowerCase();
      return !needle || n.indexOf(needle) === 0 ||
        n.slice(n.indexOf(':') + 1).indexOf(needle) === 0 || n.indexOf(needle) >= 0;
    }).sort(function (a, b) { return rank(a, needle) - rank(b, needle); });

    if (state.selected >= state.filtered.length) state.selected = 0;
    var box = els['command-popup'];
    box.innerHTML = '';
    box.hidden = false;

    if (!state.filtered.length) {
      var empty = document.createElement('div');
      empty.className = 'cmd-empty';
      empty.textContent = t('noCommand');
      box.appendChild(empty);
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
      row.addEventListener('mousedown', function (ev) {
        ev.preventDefault();
        state.selected = i;
        accept();
      });
      box.appendChild(row);
    });
  }

  function rank(c, needle) {
    if (!needle) return 0;
    var n = c.name.toLowerCase();
    if (n.indexOf(needle) === 0) return 0;
    if (n.slice(n.indexOf(':') + 1).indexOf(needle) === 0) return 1;
    return 2;
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
    els.input.selectionStart = els.input.selectionEnd = cmd.name.length + 2;
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
