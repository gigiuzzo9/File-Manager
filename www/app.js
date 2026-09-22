// ============================================================
//  FILE MANAGER — app.js (versione base, senza plugin custom)
// ============================================================

// ---------- ICONE INLINE ----------
const ICONS = {
  search: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>`,
  settings: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.36.4.66.73.86.34.2.72.3 1.11.3H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`,
  folder: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>`,
  folderAdd: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M12 11v6M9 14h6"/></svg>`,
  list: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>`,
  grid: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>`,
  images: `<svg viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-4.5-4.5L7 21"/></svg>`,
  audio: `<svg viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  video: `<svg viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m22 8-6 4 6 4V8z"/><rect x="2" y="6" width="14" height="12" rx="2"/></svg>`,
  documents: `<svg viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 13h6M9 17h6"/></svg>`,
};

// ---------- IMPOSTAZIONI ----------
const DEFAULT_SETTINGS = {
  theme: 'light',
  accent: '#3b82f6',
  showExt: true,
  confirmDelete: true,
  foldersFirst: true,
};

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem('fm-settings') || '{}');
    return { ...DEFAULT_SETTINGS, ...saved };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function saveSettings() {
  localStorage.setItem('fm-settings', JSON.stringify(state.settings));
}

// ---------- STATO ----------
const state = {
  settings: loadSettings(),
  view: 'list',
  sort: 'name',
  path: '/',
  files: [],
  storages: [{
    id: 'internal',
    name: 'Memoria interna',
    used: null,
    total: null,
  }],
  clipboard: null,
};

// ---------- INIT ----------
function init() {
  applySettings();
  injectIcons();
  bindEvents();
  bindSettings();
  renderStorages();
  renderFiles();
}

// ---------- MEMORIE ----------
function renderStorages() {
  const el = document.getElementById('storages');
  if (!state.storages.length) { el.innerHTML = ''; return; }
  el.innerHTML = state.storages.map(s => `
    <div class="storage">
      <div><strong>${s.name}</strong></div>
      <div class="meta">Info non disponibili</div>
    </div>`).join('');
}

// ---------- RENDER ----------
function sortedFiles() {
  const arr = [...state.files];
  arr.sort((a, b) => {
    if (state.settings.foldersFirst) {
      if (a.type === 'folder' && b.type !== 'folder') return -1;
      if (b.type === 'folder' && a.type !== 'folder') return 1;
    }
    if (state.sort === 'name') return a.name.localeCompare(b.name);
    return 0;
  });
  return arr;
}

function iconFor(item) {
  if (item.type === 'folder') return 'folder';
  if (item.type === 'img')    return 'images';
  if (item.type === 'video')  return 'video';
  if (item.type === 'audio')  return 'audio';
  return 'documents';
}

function renderFiles() {
  const list = document.getElementById('file-list');
  list.className = 'file-list ' + (state.view === 'list' ? 'list-view' : 'grid-view');
  const arr = sortedFiles();

  if (!arr.length) {
    list.innerHTML = '<div class="loading">Cartella vuota</div>';
    return;
  }

  list.innerHTML = arr.map((f, i) => `
    <div class="file-item" data-index="${i}">
      <span class="icon">${ICONS[iconFor(f)]}</span>
      <div class="name">${f.name}</div>
    </div>`).join('');
}

function updatePathBar() {
  const el = document.getElementById('path-bar');
  if (el) el.textContent = state.path;
}

// ---------- EVENTI ----------
function bindEvents() {
  document.getElementById('search').addEventListener('input', e => {
    const q = e.target.value.toLowerCase();
    const list = document.getElementById('file-list');
    const results = sortedFiles().filter(f => f.name.toLowerCase().includes(q));
    list.innerHTML = results.map(f => `
      <div class="file-item">
        <span class="icon">${ICONS[iconFor(f)]}</span>
        <div class="name">${f.name}</div>
      </div>
    `).join('');
  });

  document.querySelectorAll('.cat-btn').forEach(b => {
    b.addEventListener('click', () => alert('Categoria: ' + b.dataset.cat));
  });

  document.getElementById('add-folder-btn').addEventListener('click', () => {
    const n = prompt('Nome nuova cartella:');
    if (n) {
      state.files.unshift({ name: n, type: 'folder' });
      renderFiles();
    }
  });

  document.getElementById('sort-select').addEventListener('change', e => {
    state.sort = e.target.value;
    renderFiles();
  });

  document.getElementById('view-toggle').addEventListener('click', () => {
    state.view = state.view === 'list' ? 'grid' : 'list';
    document.getElementById('view-toggle').innerHTML =
      state.view === 'list' ? ICONS.list : ICONS.grid;
    renderFiles();
  });
}

// ---------- ICONE NEI BOTTONI ----------
function injectIcons() {
  document.querySelectorAll('[data-icon]').forEach(el => {
    const name = el.dataset.icon;
    if (ICONS[name]) el.innerHTML = ICONS[name];
  });
  const vt = document.getElementById('view-toggle');
  if (vt) vt.innerHTML = state.view === 'list' ? ICONS.list : ICONS.grid;
}

// ---------- IMPOSTAZIONI ----------
function applySettings() {
  const s = state.settings;
  const isDark = s.theme === 'dark' ||
    (s.theme === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.body.classList.toggle('dark', isDark);
  document.documentElement.style.setProperty('--accent', s.accent);
}

function bindSettings() {
  const panel = document.getElementById('settings-panel');
  const overlay = document.getElementById('settings-overlay');

  const openPanel = () => { panel.classList.remove('hidden'); overlay.classList.remove('hidden'); syncSettingsUI(); };
  const closePanel = () => { panel.classList.add('hidden'); overlay.classList.add('hidden'); };

  document.getElementById('settings-btn').addEventListener('click', openPanel);
  document.getElementById('settings-close').addEventListener('click', closePanel);
  overlay.addEventListener('click', closePanel);

  const bind = (id, key, type = 'value') => {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('input', () => {
      if (type === 'checkbox') state.settings[key] = el.checked;
      else state.settings[key] = el.value;
      saveSettings();
      applySettings();
      renderFiles();
    });
  };

  bind('set-theme', 'theme');
  bind('set-accent', 'accent');
  bind('set-showext', 'showExt', 'checkbox');
  bind('set-confirmdelete', 'confirmDelete', 'checkbox');
  bind('set-foldersfirst', 'foldersFirst', 'checkbox');

  document.getElementById('reset-settings').addEventListener('click', () => {
    if (!confirm('Ripristinare le impostazioni?')) return;
    state.settings = { ...DEFAULT_SETTINGS };
    saveSettings();
    applySettings();
    syncSettingsUI();
    renderFiles();
  });
}

function syncSettingsUI() {
  const s = state.settings;
  document.getElementById('set-theme').value = s.theme;
  document.getElementById('set-accent').value = s.accent;
  document.getElementById('set-showext').checked = s.showExt;
  document.getElementById('set-confirmdelete').checked = s.confirmDelete;
  document.getElementById('set-foldersfirst').checked = s.foldersFirst;
}

// ---------- GO ----------
window.addEventListener('load', init);
