// ============================================================
//  FILE MANAGER — app.js (versione base, senza plugin custom)
// ============================================================

// ---------- PERCORSI ICONE (.png) ----------
const ICON_FILES = {
  search:     'icons/search.png',
  settings:   'icons/settings.png',
  folder:     'icons/folder.png',
  folderAdd:  'icons/folder-add.png',
  list:       'icons/list.png',
  grid:       'icons/grid.png',
  images:     'icons/images.png',
  audio:      'icons/audio.png',
  video:      'icons/video.png',
  documents:  'icons/documents.png',
};

const THEMED_ICONS = ['search', 'settings', 'folder', 'folderAdd', 'list', 'grid'];

function iconHTML(name, extraClass = '') {
  const src = ICON_FILES[name];
  if (!src) return '';
  const themed = THEMED_ICONS.includes(name) ? ' themed-icon' : '';
  return `<img src="${src}" alt="" class="icon-img ${extraClass}${themed}">`;
}

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
      <span class="icon">${iconHTML(iconFor(f))}</span>
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
        <span class="icon">${iconHTML(iconFor(f))}</span>
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
      iconHTML(state.view === 'list' ? 'list' : 'grid');
    renderFiles();
  });
}

// ---------- ICONE NEI BOTTONI ----------
function injectIcons() {
  document.querySelectorAll('[data-icon]').forEach(el => {
    const name = el.dataset.icon;
    if (ICON_FILES[name]) el.innerHTML = iconHTML(name);
  });
  const vt = document.getElementById('view-toggle');
  if (vt) vt.innerHTML = iconHTML(state.view === 'list' ? 'list' : 'grid');
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
