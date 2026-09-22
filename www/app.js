// ============================================================
//  FILE MANAGER — app.js
// ============================================================

// ---------- ICONE ----------
const ICONS = {
  // Monocolore
  settings: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.36.4.66.73.86.34.2.72.3 1.11.3H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`,
  folder: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>`,
  folderAdd: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M12 11v6M9 14h6"/></svg>`,
  list: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>`,
  grid: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>`,
  // Colorate
  images: `<svg viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-4.5-4.5L7 21"/></svg>`,
  audio: `<svg viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  video: `<svg viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m22 8-6 4 6 4V8z"/><rect x="2" y="6" width="14" height="12" rx="2"/></svg>`,
  documents: `<svg viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 13h6M9 17h6"/></svg>`,
};

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
  { name: 'Documenti',     type: 'folder', size: 0,       date: '2026-01-10' },
  { name: 'Foto',          type: 'folder', size: 0,       date: '2026-02-01' },
  { name: 'Musica',        type: 'folder', size: 0,       date: '2026-01-20' },
  { name: 'relazione.pdf', type: 'pdf',    size: 1.2e6,   date: '2026-03-01' },
  { name: 'foto.jpg',      type: 'img',    size: 3.4e6,   date: '2026-02-15' },
  { name: 'video.mp4',     type: 'video',  size: 4.5e7,   date: '2026-01-05' },
];

// ---------- INIT ----------
function init() {
  document.body.classList.toggle('dark', state.theme === 'dark');
  injectIcons();
  renderStorages();
  renderFiles();
  bindEvents();
}

function injectIcons() {
  document.querySelectorAll('[data-icon]').forEach(el => {
    const name = el.dataset.icon;
    if (ICONS[name]) el.innerHTML = ICONS[name];
  });
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
  if (item.type === 'folder') return ICONS.folder;
  if (item.type === 'img')    return ICONS.images;
  if (item.type === 'video')  return ICONS.video;
  if (item.type === 'audio')  return ICONS.audio;
  return ICONS.documents;
}

function renderFiles() {
  const list = document.getElementById('file-list');
  list.className = 'file-list ' + (state.view === 'list' ? 'list-view' : 'grid-view');
  const arr = sortedFiles();
  list.innerHTML = arr.map((f, i) => `
    <div class="file-item" data-index="${i}">
      <span class="icon">${iconFor(f)}</span>
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
  m.style.left = x + 'px';
  m.style.top = y + 'px';
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
        <span class="icon">${iconFor(f)}</span>
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
    document.querySelector('#view-toggle').innerHTML = state.view === 'list' ? ICONS.list : ICONS.grid;
    renderFiles();
  });
}

init();
