/*
 * The conversation area, and nothing else.
 *
 * This is the first half of the move off Swing: the composer, sidebar and status strip are still
 * native, so everything here is about showing messages the plugin has already turned into HTML.
 * It owns no conversation state — the plugin decides what is visible and sends it over.
 */
(function () {
  'use strict';

  var els = {};
  var atBottom = true;

  function send(msg) {
    if (window.__piSend) window.__piSend(JSON.stringify(msg));
  }

  function init() {
    ['scroller', 'empty', 'earlier', 'messages', 'streaming'].forEach(function (id) {
      els[id] = document.getElementById(id);
    });

    els.scroller.addEventListener('scroll', function () {
      var el = els.scroller;
      atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    });

    // Copy buttons and the "load earlier" row live inside plugin-produced HTML, so they are
    // handled by delegation rather than wired up per message.
    document.addEventListener('click', function (e) {
      var t = e.target;
      var copy = t.closest && t.closest('.code-copy');
      if (copy) {
        var wrap = copy.closest('.code-wrap');
        var pre = wrap && wrap.querySelector('pre.code');
        if (pre) send({ type: 'copy', text: pre.textContent });
        return;
      }
      if (t.closest && t.closest('.load-earlier')) send({ type: 'loadEarlier' });
    });

    send({ type: 'ready' });
  }

  window.pi = {
    on: function (e) {
      var h = handlers[e.type];
      if (h) h(e);
    }
  };

  var handlers = {
    theme: function (e) {
      var root = document.documentElement;
      Object.keys(e.vars || {}).forEach(function (k) {
        root.style.setProperty('--' + k, e.vars[k]);
      });
    },

    empty: function (e) {
      els.empty.hidden = false;
      els.earlier.innerHTML = '';
      els.messages.innerHTML = '';
      els.streaming.innerHTML = '';
      var title = els.empty.querySelector('.empty-title');
      var path = els.empty.querySelector('.empty-path');
      if (title) title.textContent = e.title || '';
      if (path) path.textContent = e.path || '';
    },

    /** Whole transcript replaced — a session switch. */
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
      if (atBottom) scrollToBottom();
    },

    /** Older messages from the chunked backfill, inserted above what is on screen. */
    prepend: function (e) {
      els.earlier.innerHTML = e.earlier || '';
      var before = els.scroller.scrollHeight;
      var wrap = document.createElement('div');
      wrap.innerHTML = e.html || '';
      var first = els.messages.firstChild;
      while (wrap.lastChild) els.messages.insertBefore(wrap.lastChild, first);
      // Content grew above the viewport; hold the reading position steady.
      if (atBottom) scrollToBottom();
      else els.scroller.scrollTop += els.scroller.scrollHeight - before;
    },

    /** The live reply; replaced wholesale on each flush. */
    streaming: function (e) {
      if (e.html) els.empty.hidden = true;
      els.streaming.innerHTML = e.html || '';
      if (atBottom) scrollToBottom();
    },

    scrollToBottom: function () { scrollToBottom(); }
  };

  function appendHtml(parent, html) {
    var wrap = document.createElement('div');
    wrap.innerHTML = html || '';
    while (wrap.firstChild) parent.appendChild(wrap.firstChild);
  }

  function scrollToBottom() {
    // One assignment, no animation: the Swing build learned the hard way that an interpolated
    // scroll repaints every frame of the way down.
    els.scroller.scrollTop = els.scroller.scrollHeight;
    atBottom = true;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
