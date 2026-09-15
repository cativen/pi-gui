(function () {
  'use strict';

  var els = {};
  var strings = {};
  var state = { settings: {}, providers: [], skills: [], packages: [], projectAvailable: false };
  var early = [];

  function send(message) {
    if (window.__piSend) window.__piSend(JSON.stringify(message));
  }

  function t(key) { return strings[key] || key; }
  function esc(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  var ICONS = {
    back: '<path d="m10.5 4.5-4.5 4.5 4.5 4.5M6.5 9H15"/>',
    general: '<circle cx="9" cy="9" r="2.25"/><path d="M9 2.75v1.5M9 13.75v1.5M2.75 9h1.5M13.75 9h1.5M4.6 4.6l1.05 1.05M12.35 12.35l1.05 1.05M13.4 4.6l-1.05 1.05M5.65 12.35 4.6 13.4"/>',
    provider: '<rect x="3" y="4" width="12" height="10" rx="2"/><path d="M6 8h6M6 11h4"/>',
    skill: '<path d="m9 2 1.35 4.15L14.5 7.5l-4.15 1.35L9 13l-1.35-4.15L3.5 7.5l4.15-1.35L9 2Z"/><path d="m14 11 .55 1.45L16 13l-1.45.55L14 15l-.55-1.45L12 13l1.45-.55L14 11Z"/>',
    plugin: '<path d="M6.5 3H4a1 1 0 0 0-1 1v2.5a2 2 0 1 1 0 4V14a1 1 0 0 0 1 1h3.5a2 2 0 1 1 4 0H14a1 1 0 0 0 1-1v-3.5a2 2 0 1 1 0-4V4a1 1 0 0 0-1-1h-3.5a2 2 0 1 1-4 0Z"/>',
    terminal: '<path d="m3.5 5 3.5 3.5L3.5 12M9 12.5h5.5"/>'
  };

  function icon(name) {
    return '<svg class="settings-icon" viewBox="0 0 18 18" width="18" height="18" fill="none" ' +
      'stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true">' + ICONS[name] + '</svg>';
  }

  function init() {
    ['settings-title', 'settings-nav', 'reset-settings', 'embedded-toolbar', 'settings-back',
      'settings-save-state', 'theme-options', 'conversation-options',
      'font-size', 'font-size-value', 'language-options', 'import-provider', 'import-provider-db',
      'providers-list', 'providers-status', 'add-skill', 'skills-list', 'skills-status',
      'refresh-packages', 'open-packages', 'package-source', 'install-package', 'package-scopes',
      'package-query', 'search-packages', 'package-results', 'plugins-status', 'packages-summary',
      'packages-list', 'pi-path', 'choose-pi', 'pi-detected', 'extra-args', 'modal', 'modal-title',
      'modal-body', 'modal-close'].forEach(function (id) { els[id] = document.getElementById(id); });

    els['reset-settings'].addEventListener('click', function () { send({ type: 'resetDraft' }); });
    els['settings-back'].addEventListener('click', function () { send({ type: 'closeSettings' }); });
    els['font-size'].addEventListener('input', function () {
      els['font-size-value'].textContent = this.value + 'px';
      updateDraft('fontSize', Number(this.value));
    });
    // Save text fields once editing is complete; sending every keystroke across JCEF is wasteful.
    els['pi-path'].addEventListener('change', function () { updateDraft('piPath', this.value); });
    els['extra-args'].addEventListener('change', function () { updateDraft('extraArgs', this.value); });
    els['choose-pi'].addEventListener('click', function () { send({ type: 'choosePi' }); });
    els['import-provider'].addEventListener('click', function () { send({ type: 'importProvidersAuto' }); });
    els['import-provider-db'].addEventListener('click', function () { send({ type: 'importProvidersDb' }); });
    els['add-skill'].addEventListener('click', openSkillSearch);
    els['refresh-packages'].addEventListener('click', function () { send({ type: 'refreshPackages' }); });
    els['open-packages'].addEventListener('click', function () { send({ type: 'openPackages' }); });
    els['install-package'].addEventListener('click', installPackage);
    els['search-packages'].addEventListener('click', searchPackages);
    els['package-query'].addEventListener('keydown', function (event) { if (event.key === 'Enter') searchPackages(); });
    els['modal-close'].addEventListener('click', closeModal);
    els.modal.addEventListener('mousedown', function (event) { if (event.target === els.modal) closeModal(); });
    document.addEventListener('keydown', function (event) { if (event.key === 'Escape') closeModal(); });

    var buffered = early; early = []; buffered.forEach(window.pi.on);
    send({ type: 'ready' });
  }

  window.pi = {
    on: function (event) {
      if (!els['settings-nav']) { early.push(event); return; }
      var handler = handlers[event.type];
      if (handler) handler(event);
    },
    __bridgeReady: function () { send({ type: 'ready' }); }
  };

  var handlers = {
    theme: function (event) {
      Object.keys(event.vars || {}).forEach(function (key) {
        document.documentElement.style.setProperty('--' + key, event.vars[key]);
      });
    },
    i18n: function (event) {
      strings = event.strings || {};
      paintChrome();
      paintAll();
    },
    settings: function (event) {
      state.settings = event.value || {};
      state.projectAvailable = !!state.settings.projectAvailable;
      document.body.classList.toggle('embedded-settings', !!state.settings.embedded);
      els['embedded-toolbar'].hidden = !state.settings.embedded;
      paintSettings();
    },
    providers: function (event) { state.providers = event.items || []; paintProviders(); },
    skills: function (event) { state.skills = event.items || []; paintSkills(); },
    packages: function (event) {
      state.packages = event.items || [];
      els['packages-summary'].textContent = event.summary || '';
      paintPackages();
    },
    skillResults: function (event) { paintSkillResults(event.items || []); },
    packageResults: function (event) { paintPackageResults(event.items || []); },
    status: function (event) {
      var target = els[event.area + '-status'];
      if (!target) return;
      target.textContent = event.message || '';
      target.className = 'inline-status' + (event.busy ? ' busy' : '') + (event.error ? ' error' : '');
      var skillResults = event.area === 'skills' && document.getElementById('skill-results');
      if (skillResults && event.busy) skillResults.innerHTML = '<div class="resource-empty"><p>' + esc(event.message || '') + '</p></div>';
      document.querySelectorAll('[data-busy-area="' + event.area + '"]').forEach(function (button) {
        button.disabled = !!event.busy;
      });
    },
    saved: function (event) {
      var target = els['settings-save-state'];
      target.textContent = '✓ ' + (event.message || '');
      target.classList.add('visible');
      window.clearTimeout(target._hideTimer);
      target._hideTimer = window.setTimeout(function () { target.classList.remove('visible'); }, 1800);
    }
  };

  function paintChrome() {
    els['settings-title'].textContent = t('settings.title');
    els['settings-back'].innerHTML = icon('back') + '<span>' + esc(t('settings.backToChat')) + '</span>';
    els['reset-settings'].textContent = '↶  ' + t('settings.reset');
    document.querySelectorAll('[data-label]').forEach(function (node) { node.textContent = t(node.dataset.label); });
    var tabs = [
      ['general', 'general', 'settings.tab.general'], ['providers', 'provider', 'settings.tab.providers'],
      ['skills', 'skill', 'settings.tab.skills'], ['plugins', 'plugin', 'settings.tab.plugins'],
      ['cli', 'terminal', 'settings.tab.cli']
    ];
    els['settings-nav'].innerHTML = tabs.map(function (tab, index) {
      return '<button class="settings-nav-item' + (index === 0 ? ' active' : '') + '" data-page="' + tab[0] +
        '" type="button"><span aria-hidden="true">' + icon(tab[1]) + '</span>' + esc(t(tab[2])) + '</button>';
    }).join('');
    els['settings-nav'].querySelectorAll('button').forEach(function (button) {
      button.addEventListener('click', function () { showPage(button.dataset.page); });
    });
    els['import-provider'].textContent = t('providers.import.auto');
    els['import-provider-db'].textContent = t('providers.import.db');
    els['add-skill'].textContent = '+ ' + t('skills.add');
    els['refresh-packages'].textContent = '↻ ' + t('plugins.refresh');
    els['install-package'].textContent = t('plugins.install');
    els['search-packages'].textContent = t('plugins.search.action');
    els['package-query'].placeholder = t('plugins.search.placeholder');
  }

  function showPage(page) {
    document.querySelectorAll('.settings-page').forEach(function (node) { node.classList.toggle('active', node.dataset.page === page); });
    document.querySelectorAll('.settings-nav-item').forEach(function (node) { node.classList.toggle('active', node.dataset.page === page); });
  }

  function paintAll() { paintSettings(); paintProviders(); paintSkills(); paintPackages(); }

  function paintSettings() {
    var value = state.settings;
    if (!value.theme) return;
    els['theme-options'].innerHTML = choices('theme', value.theme, [
      ['SYSTEM', 'settings.appearance.system', '◐'], ['LIGHT', 'settings.appearance.light', '☀'], ['DARK', 'settings.appearance.dark', '☾']
    ]);
    els['language-options'].innerHTML = choices('language', value.language, [
      ['en', 'settings.language.en', 'EN'], ['zh-CN', 'settings.language.zhCN', '简'], ['zh-TW', 'settings.language.zhTW', '繁']
    ]);
    els['conversation-options'].innerHTML = [
      ['showThinking', 'settings.showThinking'], ['expandThinking', 'settings.expandThinking'],
      ['expandToolCalls', 'settings.expandToolCalls'], ['sendOnEnter', 'settings.sendOnEnter']
    ].map(function (item) { return toggleRow(item[0], item[1], !!value[item[0]]); }).join('');
    bindSettingsInputs();
    els['font-size'].min = value.fontMin; els['font-size'].max = value.fontMax; els['font-size'].value = value.fontSize;
    els['font-size-value'].textContent = value.fontSize + 'px';
    els['pi-path'].value = value.piPath || ''; els['extra-args'].value = value.extraArgs || '';
    els['pi-detected'].textContent = value.detecting ? t('settings.detecting') :
      (value.detected ? t('settings.detected') + ': ' + value.detected : t('settings.cli.notFound'));
    els['pi-detected'].classList.toggle('error', !value.detecting && !value.detected);
  }

  function choices(field, selected, items) {
    return items.map(function (item) {
      return '<button type="button" class="choice' + (selected === item[0] ? ' selected' : '') + '" data-field="' + field + '" data-value="' + item[0] + '">' +
        '<span class="choice-icon">' + item[2] + '</span><span>' + esc(t(item[1])) + '</span><i>✓</i></button>';
    }).join('');
  }

  function toggleRow(field, key, checked) {
    return '<label class="setting-row"><span><strong>' + esc(t(key)) + '</strong></span><input class="switch-input" data-field="' + field + '" type="checkbox" ' + (checked ? 'checked' : '') + '><span class="switch" aria-hidden="true"></span></label>';
  }

  function bindSettingsInputs() {
    document.querySelectorAll('.choice[data-field]').forEach(function (button) {
      button.addEventListener('click', function () { updateDraft(button.dataset.field, button.dataset.value); state.settings[button.dataset.field] = button.dataset.value; paintSettings(); });
    });
    document.querySelectorAll('.switch-input[data-field]').forEach(function (input) {
      input.addEventListener('change', function () { updateDraft(input.dataset.field, input.checked); state.settings[input.dataset.field] = input.checked; });
    });
  }

  function updateDraft(field, value) { send({ type: 'updateDraft', field: field, value: value }); }

  function paintProviders() {
    if (!els['providers-list']) return;
    if (!state.providers.length) { els['providers-list'].innerHTML = emptyState('◇', t('providers.empty.hint')); return; }
    var groups = [['CLAUDE_CODE', 'providers.claudeSection'], ['CODEX', 'providers.codexSection']];
    els['providers-list'].innerHTML = groups.map(function (group) {
      var items = state.providers.filter(function (item) { return item.kind === group[0]; });
      if (!items.length) return '';
      return '<div class="resource-section"><div class="section-title"><h2>' + esc(t(group[1])) + '</h2><span>' + items.length + '</span></div>' +
        items.map(providerCard).join('') + '</div>';
    }).join('');
    els['providers-list'].querySelectorAll('[data-action]').forEach(function (button) {
      button.addEventListener('click', function () {
        var provider = state.providers.find(function (item) { return item.id === button.dataset.id; });
        if (button.dataset.action === 'enable') send({ type: 'enableProvider', id: button.dataset.id });
        if (button.dataset.action === 'edit' && provider) openProviderEditor(provider);
        if (button.dataset.action === 'delete') send({ type: 'deleteProvider', id: button.dataset.id });
      });
    });
  }

  function providerCard(provider) {
    return '<article class="resource-card"><div class="resource-icon provider-icon">' + (provider.kind === 'CODEX' ? 'O' : 'A') + '</div><div class="resource-copy"><div class="resource-name">' + esc(provider.name) +
      (provider.current ? '<span class="badge active">● ' + esc(t('providers.current')) + '</span>' : '') + '</div><code>' + esc(provider.baseUrl) + '</code><p>' + esc((provider.models || []).join(' · ')) + '</p></div>' +
      '<div class="resource-actions"><button class="button small" data-action="enable" data-id="' + esc(provider.id) + '">' + esc(t('providers.enable')) + '</button><button class="button small ghost" data-action="edit" data-id="' + esc(provider.id) + '">' + esc(t('providers.edit')) + '</button><button class="button small danger-text" data-action="delete" data-id="' + esc(provider.id) + '">' + esc(t('providers.delete')) + '</button></div></article>';
  }

  function openProviderEditor(provider) {
    openModal(t('providers.dialog.title'), '<div class="modal-form">' +
      fieldHtml('provider-name', 'providers.name', provider.name) + fieldHtml('provider-url', 'providers.baseUrl', provider.baseUrl) +
      fieldHtml('provider-key', 'providers.apiKey', provider.apiKey, 'password') +
      '<label class="field"><span>' + esc(t('providers.models')) + '</span><textarea id="provider-models" rows="6">' + esc((provider.models || []).join('\n')) + '</textarea></label>' +
      '<div class="modal-actions"><button id="cancel-provider" class="button">' + esc(t('settings.cancel')) + '</button><button id="save-provider" class="button primary">' + esc(t('settings.save')) + '</button></div></div>');
    document.getElementById('cancel-provider').addEventListener('click', closeModal);
    document.getElementById('save-provider').addEventListener('click', function () {
      send({ type: 'saveProvider', id: provider.id, name: valueOf('provider-name'), baseUrl: valueOf('provider-url'), apiKey: valueOf('provider-key'), models: valueOf('provider-models').split(/\r?\n/) });
      closeModal();
    });
  }

  function paintSkills() {
    if (!els['skills-list']) return;
    if (!state.skills.length) { els['skills-list'].innerHTML = emptyState('✦', t('skills.empty')); return; }
    els['skills-list'].innerHTML = state.skills.map(function (skill) {
      return '<article class="resource-card"><div class="resource-icon skill-icon">✦</div><div class="resource-copy"><div class="resource-name">' + esc(skill.name) + '<span class="badge">' + esc(t(skill.scope === 'PROJECT' ? 'skills.scope.project' : 'skills.scope.global')) + '</span></div><p>' + esc(skill.description || t('skills.selectHint')) + '</p><code>' + esc(skill.displayPath) + '</code></div>' +
        '<label class="resource-toggle"><input class="switch-input skill-toggle" type="checkbox" data-path="' + esc(skill.path) + '" ' + (skill.enabled ? 'checked' : '') + '><span class="switch"></span></label></article>';
    }).join('');
    els['skills-list'].querySelectorAll('.skill-toggle').forEach(function (input) {
      input.addEventListener('change', function () { send({ type: 'toggleSkill', path: input.dataset.path, enabled: input.checked }); });
    });
  }

  function openSkillSearch() {
    openModal(t('skills.add'), '<div class="modal-form"><label class="field"><span>' + esc(t('skills.search')) + '</span><div class="input-action"><input id="skill-query" type="search" placeholder="' + esc(t('skills.search.placeholder')) + '"><button id="search-skills" class="button primary">' + esc(t('skills.search')) + '</button></div><small>' + esc(t('skills.search.hint')) + '</small></label><div id="skill-results" class="search-results large"></div></div>');
    function search() { send({ type: 'searchSkills', query: valueOf('skill-query') }); }
    document.getElementById('search-skills').addEventListener('click', search);
    document.getElementById('skill-query').addEventListener('keydown', function (event) { if (event.key === 'Enter') search(); });
    document.getElementById('skill-query').focus();
  }

  function paintSkillResults(items) {
    var host = document.getElementById('skill-results'); if (!host) return;
    host.innerHTML = items.length ? items.map(function (item) {
      return '<div class="search-result"><div><strong>' + esc(item.name) + '</strong><code>' + esc(item.id) + '</code><small>' + esc(item.installs) + '</small></div><div><button class="button small install-skill" data-busy-area="skills" data-id="' + esc(item.id) + '" data-scope="GLOBAL">' + esc(t('skills.install.global')) + '</button>' +
        '<button class="button small install-skill" data-busy-area="skills" data-id="' + esc(item.id) + '" data-scope="PROJECT" ' + (state.projectAvailable ? '' : 'disabled') + '>' + esc(t('skills.install.project')) + '</button></div></div>';
    }).join('') : emptyState('⌕', t('skills.search.none'));
    host.querySelectorAll('.install-skill').forEach(function (button) { button.addEventListener('click', function () { send({ type: 'installSkill', id: button.dataset.id, scope: button.dataset.scope }); }); });
  }

  function paintPackages() {
    if (!els['packages-list']) return;
    els['package-scopes'].innerHTML = scopeChoices('package-scope', state.projectAvailable);
    if (!state.packages.length) { els['packages-list'].innerHTML = emptyState('⬡', t('plugins.empty')); return; }
    els['packages-list'].innerHTML = state.packages.map(function (item) {
      return '<article class="resource-card"><div class="resource-icon plugin-icon">⬡</div><div class="resource-copy"><div class="resource-name">' + esc(item.name) + '<span class="badge">' + esc(t(item.scope === 'PROJECT' ? 'plugins.scope.project' : 'plugins.scope.global')) + '</span></div><code>' + esc(item.source) + '</code></div><button class="button small danger-text remove-package" data-source="' + esc(item.source) + '" data-scope="' + item.scope + '">' + esc(t('plugins.remove')) + '</button></article>';
    }).join('');
    els['packages-list'].querySelectorAll('.remove-package').forEach(function (button) { button.addEventListener('click', function () { send({ type: 'removePackage', source: button.dataset.source, scope: button.dataset.scope }); }); });
  }

  function searchPackages() { send({ type: 'searchPackages', query: els['package-query'].value }); }
  function installPackage() { send({ type: 'installPackage', source: els['package-source'].value, scope: selectedScope() }); }
  function selectedScope() { var checked = document.querySelector('input[name="package-scope"]:checked'); return checked ? checked.value : 'GLOBAL'; }

  function paintPackageResults(items) {
    els['package-results'].innerHTML = items.length ? items.map(function (item) {
      return '<button type="button" class="search-result result-button" data-source="' + esc(item.source) + '"><div><strong>' + esc(item.name) + '</strong><p>' + esc(item.description) + '</p><small>' + esc([item.types, item.downloads].filter(Boolean).join(' · ')) + '</small></div><span>＋</span></button>';
    }).join('') : emptyState('⌕', t('plugins.search.none'));
    els['package-results'].querySelectorAll('.result-button').forEach(function (button) { button.addEventListener('click', function () { els['package-source'].value = button.dataset.source; }); });
  }

  function scopeChoices(name, projectAvailable) {
    return '<label><input type="radio" name="' + name + '" value="GLOBAL" checked> ' + esc(t('plugins.scope.global')) + '</label>' +
      '<label title="' + esc(projectAvailable ? '' : t('plugins.noProject')) + '"><input type="radio" name="' + name + '" value="PROJECT" ' + (projectAvailable ? '' : 'disabled') + '> ' + esc(t('plugins.scope.project')) + '</label>';
  }

  function fieldHtml(id, key, value, type) { return '<label class="field"><span>' + esc(t(key)) + '</span><input id="' + id + '" type="' + (type || 'text') + '" value="' + esc(value) + '" spellcheck="false"></label>'; }
  function valueOf(id) { var node = document.getElementById(id); return node ? node.value : ''; }
  function emptyState(icon, text) { return '<div class="resource-empty"><span>' + icon + '</span><p>' + esc(text) + '</p></div>'; }
  function openModal(title, body) { els['modal-title'].textContent = title; els['modal-body'].innerHTML = body; els.modal.hidden = false; }
  function closeModal() { if (els.modal) els.modal.hidden = true; }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
