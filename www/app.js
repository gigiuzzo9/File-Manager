// ============================================================
//  FILE MANAGER — app.js con Capacitor
// ============================================================

// Riferimenti ai plugin Capacitor
const { Filesystem, Directory } = Capacitor.Plugins;
const { Device } = Capacitor.Plugins;

// ---------- PERCORSI ICONE ----------
// Metti le tue icone in www/icons/ con questi nomi
const ICON_FILES = {
  search:     'icons/search.svg',
  settings:   'icons/settings.svg',
  folder:     'icons/folder.svg',
  folderAdd:  'icons/folder-add.svg',
  list:       'icons/list.svg',
  grid:       'icons/grid.svg',
  images:     'icons/images.svg',
  audio:      'icons/audio.svg',
  video:      'icons/video.svg',
  documents:  'icons/documents.svg',
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
  path: '/',              // path corrente mostrato all'utente
  currentDir: '',         // Directory di Capacitor (Documents, External, ecc.)
  currentCat: null,       // categoria attiva (images/audio/video/documents)
  clipboard: null,
  files: [],              // file/cartelle della cartella corrente
  storages: [],           // memorie rilevate
};

// ---------- INIT ----------
async function init() {
  applySettings();
  injectIcons();
  bindEvents();
  bindSettings();

  // Chiedi i permessi al primo avvio
  await requestPermissions();

  // Rileva memorie
  await detectStorages();

  // Carica la root iniziale (Documents)
  await loadDirectory('', Directory.Documents, 'Documenti');
}

// ---------- PERMESSI ----------
async function requestPermissions() {
  try {
    // Filesystem plugin non ha un metodo diretto per permessi;
    // il primo readDir scatenerà la richiesta di sistema
    console.log('Permessi verranno chiesti alla prima lettura');
  } catch (e) {
    console.warn('Errore permessi:', e);
  }
}

// ---------- MEMORIE ----------
async function detectStorages() {
  const storages = [];

  // Memoria interna: prova a leggere info da Device plugin
  try {
    const info = await Device.getInfo();
    // Info di storage reali non facilmente accessibili; mostriamo un placeholder.
    // Per dati reali serve un plugin specifico (es. @capacitor/storage-info)
    storages.push({
      id: 'internal',
      name: 'Memoria interna',
      used: null,
      total: null,
    });
  } catch (e) {
    console.warn('Errore Device:', e);
  }

  // SD Card: prova a leggere /storage/ su Android
  try {
    const result = await Filesystem.readdir({
      path: '',
      directory: Directory.External,
    });
    // Se arriviamo qui, External esiste → SD presente
    // In realtà External su Android punta alla SD card se presente
    // Non è affidabile al 100%, ma è un inizio
  } catch (e) {
    // Se fallisce, niente SD
  }

  state.storages = storages;
  renderStorages();
}

function renderStorages() {
  const el = document.getElementById('storages');
  if (!state.storages.length) {
    el.innerHTML = '';
    return;
  }

  el.innerHTML = state.storages.map(s => {
    if (s.used === null || s.total === null) {
      return `
        <div class="storage">
          <div><strong>${s.name}</strong></div>
          <div class="meta">Dati non disponibili</div>
        </div>`;
    }
    const pct = (s.used / s.total) * 100;
    return `
      <div class="storage">
        <div><strong>${s.name}</strong></div>
        <div class="meta">${s.used.toFixed(1)} GB / ${s.total.toFixed(1)} GB</div>
        <div class="bar"><span style="width:${pct}%"></span></div>
      </div>`;
  }).join('');
}

// ---------- LETTURA DIRECTORY ----------
async function loadDirectory(path, directory, displayName) {
  const list = document.getElementById('file-list');
  list.innerHTML = '<div class="loading">Caricamento...</div>';

  state.path = '/' + (displayName || path);
  state.currentDir = directory;
  updatePathBar();

  try {
    const result = await Filesystem.readdir({
      path: path,
      directory: directory,
    });

    // result.files: array di { name, type, size, mtime, uri }
    // type: 'file' | 'directory'
    const items = await Promise.all(result.files.map(async f => {
      let size = f.size || 0;
      let date = f.mtime ? new Date(f.mtime).toISOString().slice(0,10) : '';
      return {
        name: f.name,
        type: f.type === 'directory' ? 'folder' : guessType(f.name),
        size: size,
        date: date,
        uri: f.uri,
      };
    }));

    state.files = items;
    renderFiles();

  } catch (e) {
    console.error('Errore lettura directory:', e);
    list.innerHTML = `
      <div class="loading">
        ⚠️ Impossibile leggere la cartella.<br>
        <small>${e.message || ''}</small>
      </div>`;
  }
}

function guessType(name) {
  const ext = name.split('.').pop().toLowerCase();
  if (['jpg','jpeg','png','gif','webp','bmp','svg'].includes(ext)) return 'img';
  if (['mp3','wav','ogg','flac','m4a','aac'].includes(ext))          return 'audio';
  if (['mp4','mkv','avi','mov','webm','3gp'].includes(ext))           return 'video';
  if (['pdf','doc','docx','txt','xls','xlsx','ppt','pptx'].includes(ext)) return 'doc';
  return 'file';
}

// ---------- RENDER FILES ----------
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
    const newPath = (state.currentPath === undefined ? '' : state.currentPath + '/') + item.name;
    state.currentPath = newPath;
    await loadDirectory(newPath, state.currentDir, item.name);
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
    case 'open':
      onItemClick(item);
      break;

    case 'copy':
      state.clipboard = { action: 'copy', item };
      alert('Copiato: ' + item.name);
      break;

    case 'cut':
      state.clipboard = { action: 'cut', item };
      alert('Tagliato: ' + item.name);
      break;

    case 'paste':
      alert('Incolla non ancora implementato');
      break;

    case 'move':
      alert('Sposta non ancora implementato');
      break;

    case 'rename': {
      const n = prompt('Nuovo nome:', item.name);
      if (!n || n === item.name) return;
      try {
        const basePath = state.currentPath ? state.currentPath + '/' : '';
        await Filesystem.rename({
          from: basePath + item.name,
          to:   basePath + n,
          directory: state.currentDir,
        });
        item.name = n;
        renderFiles();
      } catch (e) {
        alert('Errore rinomina: ' + e.message);
      }
      break;
    }

    case 'share':
      alert('Condividi non ancora implementato');
      break;

    case 'delete': {
      if (state.settings.confirmDelete && !confirm('Eliminare ' + item.name + '?')) return;
      try {
        const basePath = state.currentPath ? state.currentPath + '/' : '';
        await Filesystem.deleteFile({
          path: basePath + item.name,
          directory: state.currentDir,
        });
        state.files = state.files.filter(f => f !== item);
        renderFiles();
      } catch (e) {
        alert('Errore eliminazione: ' + e.message);
      }
      break;
    }

    case 'info':
      alert(`${item.name}\nTipo: ${item.type}\nDimensione: ${formatSize(item.size)}\nData: ${item.date || 'n/d'}`);
      break;
  }
}

// ---------- EVENTI UI ----------
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
      state.currentCat = b.dataset.cat;

      // Mappa categoria → Directory
      // Per ora apriamo Directory.Documents filtrando
      // Nota: Capacitor non ha una Directory per ogni categoria, serve percorso
      alert('Categoria: ' + b.dataset.cat + ' (da implementare)');
    });
  });

  document.getElementById('add-folder-btn').addEventListener('click', async () => {
    const n = prompt('Nome nuova cartella:');
    if (!n) return;
    try {
      const basePath = state.currentPath ? state.currentPath + '/' : '';
      await Filesystem.mkdir({
        path: basePath + n,
        directory: state.currentDir,
        recursive: false,
      });
      await loadDirectory(state.currentPath, state.currentDir, '');
    } catch (e) {
      alert('Errore creazione: ' + e.message);
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

// ---------- INIEZIONE ICONE ----------
function injectIcons() {
  document.querySelectorAll('[data-icon]').forEach(el => {
    el.innerHTML = iconHTML(el.dataset.icon);
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

  const openPanel = () => {
    panel.classList.remove('hidden');
    overlay.classList.remove('hidden');
    syncSettingsUI();
  };
  const closePanel = () => {
    panel.classList.add('hidden');
    overlay.classList.add('hidden');
  };

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

  window.matchMedia('(prefers-color-scheme: dark)')
    .addEventListener('change', () => applySettings());
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
document.addEventListener('deviceready', init);
document.addEventListener('DOMContentLoaded', () => {
  // Se Capacitor non è pronto, fallback
  if (window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform()) {
    console.log('In attesa di deviceready...');
  } else {
    init();
  }
});
