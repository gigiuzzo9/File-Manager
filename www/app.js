// ============================================================
//  FILE MANAGER — app.js
// ============================================================

// ---------- PLUGIN CAPACITOR ----------
function getPlugin(name) {
  if (window.Capacitor?.Plugins?.[name]) return window.Capacitor.Plugins[name];
  if (window.Capacitor?.registerPlugin) {
    try { return window.Capacitor.registerPlugin(name); } catch (e) {}
  }
  return null;
}

const Filesystem = getPlugin('Filesystem');

console.log('Filesystem plugin:', Filesystem);

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
  relPath: '',
  rootDir: null,
  files: [],
  storages: [],
};

// ---------- INIT ----------
async function init() {
  applySettings();
  injectIcons();
  bindEvents();
  bindSettings();

  if (!Filesystem) {
    document.getElementById('file-list').innerHTML =
      '<div class="loading">⚠️ Plugin Filesystem non disponibile</div>';
    return;
  }

  await requestAllPermissions();

  await detectStorages();
  await loadRoot();
}

// ---------- PERMESSI ----------
async function requestAllPermissions() {
  // Capacitor Filesystem chiede automaticamente i permessi al primo accesso.
  // Ma per MANAGE_EXTERNAL_STORAGE serve un intent Android esplicito.
  try {
    // Prova prima a leggere una cartella nota per attivare la richiesta di sistema
    await Filesystem.readdir({ path: '', directory: 'EXTERNAL_STORAGE' });
  } catch (e) {
    console.log('Prima lettura ha lanciato richiesta permessi:', e.message);
  }
}

// ---------- MEMORIE ----------
async function detectStorages() {
  state.storages = [{
    id: 'internal',
    name: 'Memoria interna',
    used: null,
    total: null,
  }];
  renderStorages();
}

function renderStorages() {
  const el = document.getElementById('storages');
  if (!state.storages.length) { el.innerHTML = ''; return; }
  el.innerHTML = state.storages.map(s => `
    <div class="storage">
      <div><strong>${s.name}</strong></div>
      <div class="meta">Info non disponibili</div>
    </div>`).join('');
}

// ---------- ROOT STORAGE ----------
async function loadRoot() {
  // Su Android con MANAGE_EXTERNAL_STORAGE, il path "/" con EXTERNAL_STORAGE
  // punta a /storage/emulated/0/
  const attempts = [
    { dir: 'EXTERNAL_STORAGE', path: '',        label: 'Storage' },
    { dir: 'EXTERNAL_STORAGE', path: '/',       label: 'Storage' },
    { dir: 'EXTERNAL',         path: '',        label: 'Storage' },
    { dir: 'DOCUMENTS',        path: '',        label: 'Documenti' },
  ];

  for (const a of attempts) {
    try {
      console.log(`Provo ${a.dir} path="${a.path}"...`);
      const res = await Filesystem.readdir({ path: a.path, directory: a.dir });
      console.log(`✅ OK con ${a.dir}:`, res);
      state.rootDir = a.dir;
      state.relPath = a.path;
      state.path = '/' + a.label;
      updatePathBar();
      state.files = res.files.map(f => ({
        name: f.name,
        type: f.type === 'directory' ? 'folder' : guessType(f.name),
        size: f.size || 0,
        date: f.mtime ? new Date(f.mtime).toISOString().slice(0,10) : '',
      }));
      renderFiles();
      return;
    } catch (e) {
      console.warn(`❌ Fallito ${a.dir}/${a.path}:`, e.message);
    }
  }

  document.getElementById('file-list').innerHTML = `
    <div class="loading">
      ⚠️ Impossibile accedere allo storage.<br>
      <small>Vai in Impostazioni Android → App → File Manager → Autorizzazioni<br>
      e abilita "File e media" / "Gestisci tutti i file".</small>
    </div>`;
}

// ---------- LETTURA DIRECTORY ----------
async function loadDirectory(path, label) {
  const list = document.getElementById('file-list');
  list.innerHTML = '<div class="loading">Caricamento...</div>';
  state.relPath = path;
  state.path = '/' + label;
  updatePathBar();

  try {
    const res = await Filesystem.readdir({ path, directory: state.rootDir });
    state.files = res.files.map(f => ({
      name: f.name,
      type: f.type === 'directory' ? 'folder' : guessType(f.name),
      size: f.size || 0,
      date: f.mtime ? new Date(f.mtime).toISOString().slice(0,10) : '',
    }));
    renderFiles();
  } catch (e) {
    list.innerHTML = `<div class="loading">⚠️ Errore:<br><small>${e.message}</small></div>`;
  }
}

function guessType(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (['jpg','jpeg','png','gif','webp','bmp'].includes(ext)) return 'img';
  if (['mp3','wav','ogg','flac','m4a','aac'].includes(ext))   return 'audio';
  if (['mp4','mkv','avi','mov','webm','3gp'].includes(ext))   return 'video';
  if (['pdf','doc','docx','txt','xls','xlsx','ppt','pptx'].includes(ext)) return 'doc';
  return 'file';
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
    if (state.sort === 'date') return new Date(b.date) - new Date(a.date);
    if (state.sort === 'size') return b.size - a.size;
    if (state.sort === 'type') return a.type.localeCompare(b.type);
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

  list.innerHTML = arr.map((f, i) => {
    const name = state.settings.showExt ? f.name : f.name.replace(/\.[^.]+$/, '');
    return `
      <div class="file-item" data-index="${i}">
        <span class="icon">${iconHTML(iconFor(f))}</span>
        <div class="name">${name}</div>
        <div class="meta">${f.type === 'folder' ? '' : formatSize(f.size)}</div>
      </div>`;
  }).join('');

  list.querySelectorAll('.file-item').forEach(el => {
    el.addEventListener('click', () => onItemClick(arr[el.dataset.index]));
    el.addEventListener('contextmenu', e => {
      e.preventDefault();
      showContextMenu(e.clientX, e.clientY, arr[el.dataset.index]);
    });
  });
}

function formatSize(bytes) {
  if (!bytes) return '';
  const u = ['B','KB','MB','GB'];
  let i = 0;
  while (bytes >= 1024 && i < u.length - 1) { bytes /= 1024; i++; }
  return bytes.toFixed(1) + ' ' + u[i];
}

async function onItemClick(item) {
  if (item.type === 'folder') {
    const newPath = state.relPath ? state.relPath + '/' + item.name : item.name;
    await loadDirectory(newPath, item.name);
  } else {
    alert('Apro: ' + item.name);
  }
}

function updatePathBar() {
  const el = document.getElementById('path-bar');
  if (el) el.textContent = state.path;
}

// ---------- MENU CONTESTUALE ----------
let ctxItem = null;

function showContextMenu(x, y, item) {
  ctxItem = item;
  const m = document.getElementById('context-menu');
  m.style.left = Math.min(x, window.innerWidth - 180) + 'px';
  m.style.top = Math.min(y, window.innerHeight - 320) + 'px';
  m.classList.remove('hidden');
}

document.addEventListener('click', () => {
  document.getElementById('context-menu').classList.add('hidden');
});

document.querySelectorAll('#context-menu button').forEach(btn => {
  btn.addEventListener('click', () => {
    if (!ctxItem) return;
    handleAction(btn.dataset.action, ctxItem);
  });
});

async function handleAction(action, item) {
  switch (action) {
    case 'open': onItemClick(item); break;
    case 'copy': state.clipboard = { action: 'copy', item }; alert('Copiato'); break;
    case 'cut':  state.clipboard = { action: 'cut', item };  alert('Tagliato'); break;
    case 'paste': alert('Non ancora implementato'); break;
    case 'move':  alert('Non ancora implementato'); break;
    case 'rename': {
      const n = prompt('Nuovo nome:', item.name);
      if (!n || n === item.name) return;
      try {
        const base = state.relPath ? state.relPath + '/' : '';
        await Filesystem.rename({ from: base + item.name, to: base + n, directory: state.rootDir });
        item.name = n;
        renderFiles();
      } catch (e) { alert('Errore: ' + e.message); }
      break;
    }
    case 'share': alert('Non ancora implementato'); break;
    case 'delete': {
      if (state.settings.confirmDelete && !confirm('Eliminare ' + item.name + '?')) return;
      try {
        const base = state.relPath ? state.relPath + '/' : '';
        await Filesystem.deleteFile({ path: base + item.name, directory: state.rootDir });
        state.files = state.files.filter(f => f !== item);
        renderFiles();
      } catch (e) { alert('Errore: ' + e.message); }
      break;
    }
    case 'info':
      alert(`${item.name}\nTipo: ${item.type}\nDimensione: ${formatSize(item.size)}`);
      break;
  }
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
    b.addEventListener('click', async () => {
      document.querySelectorAll('.cat-btn').forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      const map = {
        images:    { path: 'DCIM/Camera', label: 'Immagini' },
        audio:     { path: 'Music',       label: 'Audio'    },
        video:     { path: 'DCIM',        label: 'Video'    },
        documents: { path: 'Documents',   label: 'Documenti'},
      };
      const t = map[b.dataset.cat];
      if (t) await loadDirectory(t.path, t.label);
    });
  });

  document.getElementById('add-folder-btn').addEventListener('click', async () => {
    const n = prompt('Nome nuova cartella:');
    if (!n) return;
    try {
      const base = state.relPath ? state.relPath + '/' : '';
      await Filesystem.mkdir({ path: base + n, directory: state.rootDir, recursive: false });
      await loadDirectory(state.relPath, state.path.replace(/^\//, ''));
    } catch (e) { alert('Errore: ' + e.message); }
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

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applySettings());
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
window.addEventListener('load', () => {
  init();
});
