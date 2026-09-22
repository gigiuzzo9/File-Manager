// ============================================================
//  FILE MANAGER — app.js
// ============================================================

// ---------- PERCORSI ICONE ----------
// ⚙️ Modifica qui se cambi formato (png/svg/webp) o nomi file
const ICON_FILES = {
  // Monocolore (cambiano colore col tema)
  search:     'icons/search.png',
  settings:   'icons/settings.png',
  folder:     'icons/folder.png',
  folderAdd:  'icons/folder-add.png',
  list:       'icons/list.png',
  grid:       'icons/grid.png',
  // Colorate (colori fissi)
  images:     'icons/images.png',
  audio:      'icons/audio.png',
  video:      'icons/video.png',
  documents:  'icons/documents.png',
};

// Icone che devono seguire il tema (monocolore)
const THEMED_ICONS = ['search', 'settings', 'folder', 'folderAdd', 'list', 'grid'];

// ---------- GENERA HTML DELL'ICONA ----------
function iconHTML(name, extraClass = '') {
  const src = ICON_FILES[name];
  if (!src) return '';
  const themed = THEMED_ICONS.includes(name) ? ' themed-icon' : '';
  return `<img src="${src}" alt="" class="icon-img ${extraClass}${themed}">`;
}

// ---------- STATO ----------
const state = {
  theme: localStorage.getItem('theme') || 'light',
  view: 'list',
  sort: 'name',
  path: '/',
  clipboard: null,
};

// ---------- MOCK ----------
const storages = [
  { id: 'internal', name: 'Memoria interna', used: 57, total: 256 },
  { id: 'sdcard',   name: 'SD Card',          used: 12, total: 64  },
];

let files = [
  { name: 'Documenti',     type: 'folder', size: 0,     date: '2026-01-10' },
  { name: 'Foto',          type: 'folder', size: 0,     date: '2026-02-01' },
  { name: 'Musica',        type: 'folder', size: 0,     date: '2026-01-20' },
  { name: 'relazione.pdf', type: 'pdf',    size: 1.2e6, date: '2026-03-01' },
  { name: 'foto.jpg',      type: 'img',    size: 3.4e6, date: '2026-02-15' },
  { name: 'video.mp4',     type: 'video',  size: 4.5e7, date: '2026-01-05' },
];

// ---------- INIT ----------
function init() {
  document.body.classList.toggle('dark', state.theme === 'dark');
  injectIcons();
  renderStorages();
  renderFiles();
  bindEvents();
}

// ---------- INIETTA ICONE NEI BOTTONI FISSI ----------
function injectIcons() {
  // Bottoni con data-icon="nomeIcona"
  document.querySelectorAll('[data-icon]').forEach(el => {
    el.innerHTML = iconHTML(el.dataset.icon);
  });

  // Icona dentro il bottone view-toggle
  const vt = document.getElementById('view-toggle');
  if (vt) vt.innerHTML = iconHTML(state.view === 'list' ? 'list' : 'grid');
}

// ---------- STORAGES ----------
function renderStorages() {
  const el = document.getElementById('storages');
  el.innerHTML = storages.map(s => {
    const pct = (s.used / s.total) * 100;
    return `
      <div class="storage">
        <div><strong>${s.name}</strong></div>
        <div class="meta">${s.used} GB / ${s.total} GB</div>
        <div class="bar"><span style="width:${pct}%"></span></div>
      </div>`;
  }).join('');
}

// ---------- FILES ----------
function sortedFiles() {
  const arr = [...files];
  arr.sort((a, b) => {
    if (a.type === 'folder' && b.type !== 'folder') return -1;
    if (b.type === 'folder' && a.type !== 'folder') return 1;
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

  list.innerHTML = arr.map((f, i) => `
    <div class="file-item" data-index="${i}">
      <span class="icon">${iconHTML(iconFor(f))}</span>
      <div class="name">${f.name}</div>
      <div class="meta">${f.type === 'folder' ? '' : formatSize(f.size)}</div>
    </div>
  `).join('');

  list.querySelectorAll('.file-item').forEach(el => {
    el.addEventListener('click', () => {
      const f = arr[el.dataset.index];
      if (f.type === 'folder') openFolder(f.name);
      else alert('Apro: ' + f.name);
    });
    el.addEventListener('contextmenu', e => {
      e.preventDefault();
      const f = arr[el.dataset.index];
      showContextMenu(e.clientX, e.clientY, f);
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

function openFolder(name) {
  state.path = (state.path === '/' ? '' : state.path) + '/' + name;
  files = [
    { name: '..', type: 'folder', size: 0, date: '' },
    { name: 'file_esempio.txt', type: 'txt', size: 2048, date: '2026-04-01' },
  ];
  renderFiles();
}

// ---------- MENU CONTESTUALE ----------
let ctxItem = null;

function showContextMenu(x, y, item) {
  ctxItem = item;
  const m = document.getElementById('context-menu');
  m.style.left = Math.min(x, window.innerWidth - 180) + 'px';
  m.style.top = Math.min(y, window.innerHeight - 300) + 'px';
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

function handleAction(action, item) {
  switch (action) {
    case 'open': alert('Apro ' + item.name); break;
    case 'copy': state.clipboard = { action: 'copy', item }; break;
    case 'cut':  state.clipboard = { action: 'cut', item }; break;
    case 'paste': alert('Incolla in ' + state.path); break;
    case 'move': alert('Sposta ' + item.name); break;
    case 'rename': {
      const n = prompt('Nuovo nome:', item.name);
      if (n) { item.name = n; renderFiles(); }
      break;
    }
    case 'share': alert('Condividi ' + item.name); break;
    case 'delete':
      if (confirm('Eliminare ' + item.name + '?')) {
        files = files.filter(f => f !== item);
        renderFiles();
      }
      break;
    case 'info': alert(`${item.name}\nTipo: ${item.type}\nDimensione: ${formatSize(item.size)}`); break;
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
    b.addEventListener('click', () => alert('Categoria: ' + b.dataset.cat));
  });

  document.getElementById('add-folder-btn').addEventListener('click', () => {
    const n = prompt('Nome nuova cartella:');
    if (n) {
      files.unshift({ name: n, type: 'folder', size: 0, date: new Date().toISOString().slice(0,10) });
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

init();
