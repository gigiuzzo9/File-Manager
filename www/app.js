// ---------- PLUGIN ----------
function getPlugin(name) {
  if (window.Capacitor?.Plugins?.[name]) return window.Capacitor.Plugins[name];
  if (window.Capacitor?.registerPlugin) {
    try { return window.Capacitor.registerPlugin(name); } catch (e) {}
  }
  return null;
}

const Filesystem = getPlugin('Filesystem');
const FileReader = getPlugin('FileReader');  // <-- plugin nuovo

// ---------- ROOT STORAGE ----------
async function loadRoot() {
  const list = document.getElementById('file-list');

  // Verifica permesso "tutti i file"
  try {
    const { granted } = await FileReader.isAllFilesAccessGranted();
    if (!granted) {
      list.innerHTML = `
        <div class="loading">
          ⚠️ Permesso "Gestisci tutti i file" NON attivo.<br>
          <small>Impostazioni Android → App → File Manager → Autorizzazioni → File e media → attiva "Gestisci tutti i file"</small>
        </div>`;
      return;
    }
  } catch (e) {
    console.warn('Impossibile verificare permesso:', e);
  }

  // Leggi la root dello storage
  try {
    const res = await FileReader.readDir({ path: '/storage/emulated/0/' });
    state.rootDir = 'NATIVE';
    state.relPath = '/storage/emulated/0';
    state.path = '/Storage';
    updatePathBar();

    state.files = res.files.map(f => ({
      name: f.name,
      type: f.isDirectory ? 'folder' : guessType(f.name),
      size: f.size || 0,
      date: f.mtime ? new Date(f.mtime).toISOString().slice(0,10) : '',
      fullPath: f.path,
    }));
    renderFiles();

  } catch (e) {
    console.error('Errore readDir root:', e);
    list.innerHTML = `<div class="loading">⚠️ Errore:<br><small>${e.message}</small></div>`;
  }
}

// ---------- LETTURA DIRECTORY ----------
async function loadDirectory(path, label) {
  const list = document.getElementById('file-list');
  list.innerHTML = '<div class="loading">Caricamento...</div>';

  // path qui è il path relativo o assoluto
  const absPath = path.startsWith('/') ? path : (state.relPath + '/' + path);

  state.relPath = absPath;
  state.path = '/' + label;
  updatePathBar();

  try {
    const res = await FileReader.readDir({ path: absPath });
    state.files = res.files.map(f => ({
      name: f.name,
      type: f.isDirectory ? 'folder' : guessType(f.name),
      size: f.size || 0,
      date: f.mtime ? new Date(f.mtime).toISOString().slice(0,10) : '',
      fullPath: f.path,
    }));
    renderFiles();
  } catch (e) {
    list.innerHTML = `<div class="loading">⚠️ Errore:<br><small>${e.message}</small></div>`;
  }
}
