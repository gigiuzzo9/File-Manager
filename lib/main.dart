import 'dart:io';
import 'package:flutter/material.dart';

void main() {
  runApp(const FileManagerApp());
}

// ---------- MAPPA ICONE ----------
const Map<String, String> ICON_PATHS = {
  'folder':     'assets/icons/folder.png',
  'folderAdd':  'assets/icons/folder-add.png',
  'search':     'assets/icons/search.png',
  'settings':   'assets/icons/settings.png',
  'images':     'assets/icons/images.png',
  'audio':      'assets/icons/audio.png',
  'video':      'assets/icons/video.png',
  'documents':  'assets/icons/documents.png',
  'list':       'assets/icons/list.png',
  'grid':       'assets/icons/grid.png',
};

class AppIcon extends StatelessWidget {
  final String name;
  final double size;
  const AppIcon(this.name, {super.key, this.size = 28});

  @override
  Widget build(BuildContext context) {
    final path = ICON_PATHS[name];
    if (path == null) return SizedBox(width: size, height: size);
    return Image.asset(
      path,
      width: size,
      height: size,
      errorBuilder: (_, __, ___) => Icon(
        Icons.broken_image,
        size: size,
        color: Colors.grey,
      ),
    );
  }
}

// ---------- APP ----------
class FileManagerApp extends StatelessWidget {
  const FileManagerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'File Manager',
      themeMode: ThemeMode.system,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

// ---------- HOME ----------
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const String ROOT = '/storage/emulated/0';

  String currentPath = ROOT;
  List<FileSystemEntity> items = [];
  bool isGridView = false;
  String searchQuery = '';
  String? activeCategory; // null | 'images' | 'audio' | 'video' | 'documents'
  String sortBy = 'name'; // name | size | date

  @override
  void initState() {
    super.initState();
    _loadDirectory(ROOT);
  }

  // ---------- LETTURA ----------
  void _loadDirectory(String path) {
    List<FileSystemEntity> list = [];
    try {
      final dir = Directory(path);
      if (dir.existsSync()) {
        list = dir.listSync();
      }
    } catch (_) {
      list = [];
    }
    setState(() {
      currentPath = path;
      items = list;
      activeCategory = null;
    });
  }

  // ---------- FILTRI + ORDINAMENTO ----------
  List<FileSystemEntity> _filtered() {
    var list = List<FileSystemEntity>.from(items);

    // Ricerca
    if (searchQuery.isNotEmpty) {
      final q = searchQuery.toLowerCase();
      list = list.where((e) =>
        e.path.split('/').last.toLowerCase().contains(q)
      ).toList();
    }

    // Categoria
    if (activeCategory != null) {
      list = list.where((e) {
        if (e is Directory) return false;
        return _iconFor(e) == activeCategory;
      }).toList();
    }

    // Ordinamento
    list.sort((a, b) {
      final aIsDir = a is Directory;
      final bIsDir = b is Directory;
      if (aIsDir && !bIsDir) return -1;
      if (!aIsDir && bIsDir) return 1;

      if (sortBy == 'name') {
        return a.path.split('/').last.toLowerCase()
            .compareTo(b.path.split('/').last.toLowerCase());
      }
      if (sortBy == 'size') {
        int sa = 0, sb = 0;
        if (a is File) { try { sa = a.lengthSync(); } catch (_) {} }
        if (b is File) { try { sb = b.lengthSync(); } catch (_) {} }
        return sb.compareTo(sa);
      }
      if (sortBy == 'date') {
        DateTime da = DateTime(1970);
        DateTime db = DateTime(1970);
        try { da = a.statSync().modified; } catch (_) {}
        try { db = b.statSync().modified; } catch (_) {}
        return db.compareTo(da);
      }
      return 0;
    });

    return list;
  }

  // ---------- ICONE ----------
  String _iconFor(FileSystemEntity item) {
    if (item is Directory) return 'folder';
    final name = item.path.toLowerCase();
    if (name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.png')
        || name.endsWith('.gif') || name.endsWith('.webp') || name.endsWith('.bmp')) {
      return 'images';
    }
    if (name.endsWith('.mp4') || name.endsWith('.mkv') || name.endsWith('.avi')
        || name.endsWith('.mov') || name.endsWith('.webm') || name.endsWith('.3gp')) {
      return 'video';
    }
    if (name.endsWith('.mp3') || name.endsWith('.wav') || name.endsWith('.ogg')
        || name.endsWith('.flac') || name.endsWith('.m4a') || name.endsWith('.aac')) {
      return 'audio';
    }
    return 'documents';
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  // ---------- NAVIGAZIONE ----------
  void _goBack() {
    if (currentPath == ROOT) return;
    final parent = currentPath.substring(0, currentPath.lastIndexOf('/'));
    if (parent.length < ROOT.length) return;
    _loadDirectory(parent);
  }

  void _onItemTap(FileSystemEntity item, String name, bool isDir) {
    if (isDir) {
      _loadDirectory(item.path);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Apro: $name')),
      );
    }
  }

  void _showContextMenu(FileSystemEntity item, String name) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(title: Text(name), subtitle: const Text('File')),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.open_in_new),
              title: const Text('Apri'),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.copy),
              title: const Text('Copia'),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.content_cut),
              title: const Text('Taglia'),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.drive_file_rename_outline),
              title: const Text('Rinomina'),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.share),
              title: const Text('Condividi'),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.delete, color: Colors.red),
              title: const Text('Elimina', style: TextStyle(color: Colors.red)),
              onTap: () { Navigator.pop(context); },
            ),
            ListTile(
              leading: const Icon(Icons.info_outline),
              title: const Text('Proprietà'),
              onTap: () { Navigator.pop(context); },
            ),
          ],
        ),
      ),
    );
  }

  // ---------- BUILD ----------
  @override
  Widget build(BuildContext context) {
    final filtered = _filtered();

    return Scaffold(
      appBar: AppBar(
        title: Text(
          currentPath.split('/').last.isEmpty
              ? 'Storage'
              : currentPath.split('/').last,
          style: const TextStyle(fontSize: 16),
        ),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: _goBack,
        ),
      ),
      body: Column(
        children: [
          // ---------- RIGA 1: ricerca + impostazioni ----------
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    decoration: InputDecoration(
                      hintText: 'Cerca file o cartelle...',
                      prefixIcon: Padding(
                        padding: const EdgeInsets.all(8),
                        child: AppIcon('search', size: 20),
                      ),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                      isDense: true,
                      contentPadding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    onChanged: (v) => setState(() => searchQuery = v),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: () => _showSettings(context),
                  icon: AppIcon('settings', size: 24),
                ),
              ],
            ),
          ),

          // ---------- RIGA 2: categorie ----------
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                _catButton('images', 'Immagini'),
                const SizedBox(width: 8),
                _catButton('audio', 'Audio'),
                const SizedBox(width: 8),
                _catButton('video', 'Video'),
                const SizedBox(width: 8),
                _catButton('documents', 'Documenti'),
              ],
            ),
          ),

          const SizedBox(height: 12),

          // ---------- RIGA 3: azioni ----------
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                IconButton(
                  onPressed: _createFolder,
                  icon: AppIcon('folderAdd', size: 24),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: DropdownButton<String>(
                    value: sortBy,
                    isExpanded: true,
                    underline: Container(),
                    items: const [
                      DropdownMenuItem(value: 'name', child: Text('Nome')),
                      DropdownMenuItem(value: 'size', child: Text('Dimensione')),
                      DropdownMenuItem(value: 'date', child: Text('Data')),
                    ],
                    onChanged: (v) => setState(() => sortBy = v ?? 'name'),
                  ),
                ),
                IconButton(
                  onPressed: () => setState(() => isGridView = !isGridView),
                  icon: AppIcon(isGridView ? 'list' : 'grid', size: 24),
                ),
              ],
            ),
          ),

          const SizedBox(height: 4),

          // ---------- LISTA ----------
          Expanded(
            child: filtered.isEmpty
                ? const Center(child: Text('Cartella vuota'))
                : isGridView
                    ? _buildGrid(filtered)
                    : _buildList(filtered),
          ),
        ],
      ),
    );
  }

  Widget _catButton(String key, String label) {
    final isActive = activeCategory == key;
    return Expanded(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => setState(() {
          activeCategory = isActive ? null : key;
        }),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: isActive
                ? Theme.of(context).colorScheme.primary.withOpacity(0.15)
                : Theme.of(context).cardColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isActive
                  ? Theme.of(context).colorScheme.primary
                  : Colors.grey.withOpacity(0.3),
            ),
          ),
          child: Column(
            children: [
              AppIcon(key, size: 26),
              const SizedBox(height: 4),
              Text(label, style: const TextStyle(fontSize: 11)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildList(List<FileSystemEntity> list) {
    return ListView.builder(
      itemCount: list.length,
      itemBuilder: (context, i) {
        final item = list[i];
        final name = item.path.split('/').last;
        final isDir = item is Directory;
        int size = 0;
        if (item is File) {
          try { size = item.lengthSync(); } catch (_) {}
        }

        return ListTile(
          leading: AppIcon(_iconFor(item), size: 32),
          title: Text(name),
          subtitle: isDir ? null : Text(_formatSize(size)),
          onTap: () => _onItemTap(item, name, isDir),
          onLongPress: () => _showContextMenu(item, name),
        );
      },
    );
  }

  Widget _buildGrid(List<FileSystemEntity> list) {
    return GridView.builder(
      padding: const EdgeInsets.all(8),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        childAspectRatio: 0.85,
      ),
      itemCount: list.length,
      itemBuilder: (context, i) {
        final item = list[i];
        final name = item.path.split('/').last;
        final isDir = item is Directory;

        return InkWell(
          onTap: () => _onItemTap(item, name, isDir),
          onLongPress: () => _showContextMenu(item, name),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              AppIcon(_iconFor(item), size: 48),
              const SizedBox(height: 6),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  name,
                  maxLines: 2,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  // ---------- AZIONI ----------
  void _createFolder() {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Nuova cartella'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(hintText: 'Nome cartella'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Annulla'),
          ),
          ElevatedButton(
            onPressed: () {
              final name = controller.text.trim();
              Navigator.pop(context);
              if (name.isEmpty) return;
              try {
                Directory('$currentPath/$name').createSync();
                _loadDirectory(currentPath);
              } catch (e) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('Errore: $e')),
                );
              }
            },
            child: const Text('Crea'),
          ),
        ],
      ),
    );
  }

  void _showSettings(BuildContext context) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: const [
            ListTile(
              leading: Icon(Icons.palette),
              title: Text('Tema'),
              subtitle: Text('Segue il sistema'),
            ),
            ListTile(
              leading: Icon(Icons.info_outline),
              title: Text('File Manager'),
              subtitle: Text('v1.0.0'),
            ),
          ],
        ),
      ),
    );
  }
}
