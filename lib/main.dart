import 'dart:io';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

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

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const String ROOT = '/storage/emulated/0';

  String currentPath = ROOT;
  List<FileSystemEntity> items = [];
  bool isGridView = false;
  bool canRead = false;
  bool isLoading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _tryLoad();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _tryLoad();
    }
  }

  // ---------- LOGICA PERMESSI ----------
  // Non chiediamo a permission_handler "ho il permesso?".
  // Proviamo DIRETTAMENTE a leggere la cartella.
  // Se funziona → permesso ok. Se no → chiediamo.
  Future<void> _tryLoad() async {
    setState(() => isLoading = true);

    // Tentativo 1: prova a leggere la root
    if (_canReadDirectory(ROOT)) {
      setState(() {
        canRead = true;
        isLoading = false;
      });
      _loadDirectory(ROOT);
      return;
    }

    // Tentativo 2: chiedi il permesso
    await Permission.manageExternalStorage.request();

    // Tentativo 3: riprova a leggere
    if (_canReadDirectory(ROOT)) {
      setState(() {
        canRead = true;
        isLoading = false;
      });
      _loadDirectory(ROOT);
      return;
    }

    // Tentativo 4: apri le impostazioni
    await openAppSettings();

    // Tentativo 5: alla prossima apertura ricontrolla
    setState(() {
      canRead = false;
      isLoading = false;
    });
  }

  bool _canReadDirectory(String path) {
    try {
      final dir = Directory(path);
      if (!dir.existsSync()) return false;
      dir.listSync().take(1); // prova a leggere almeno un elemento
      return true;
    } catch (e) {
      return false;
    }
  }

  void _loadDirectory(String path) {
    try {
      final dir = Directory(path);
      final list = dir.listSync();
      list.sort((a, b) {
        final aIsDir = a is Directory;
        final bIsDir = b is Directory;
        if (aIsDir && !bIsDir) return -1;
        if (!aIsDir && bIsDir) return 1;
        return a.path.split('/').last.toLowerCase()
            .compareTo(b.path.split('/').last.toLowerCase());
      });
      setState(() {
        currentPath = path;
        items = list;
      });
    } catch (e) {
      setState(() {
        items = [];
      });
    }
  }

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

  @override
  Widget build(BuildContext context) {
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
        actions: [
          IconButton(
            icon: Icon(isGridView ? Icons.view_list : Icons.grid_view),
            onPressed: () => setState(() => isGridView = !isGridView),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _tryLoad,
          ),
        ],
      ),
      body: isLoading
          ? const Center(child: CircularProgressIndicator())
          : !canRead
              ? _buildPermissionScreen()
              : items.isEmpty
                  ? const Center(child: Text('Cartella vuota'))
                  : isGridView
                      ? _buildGrid()
                      : _buildList(),
    );
  }

  Widget _buildPermissionScreen() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.folder_off, size: 64, color: Colors.grey),
            const SizedBox(height: 24),
            const Text(
              'Permesso "Gestisci tutti i file" necessario',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            const Text(
              'Apri le impostazioni e attiva:\n'
              '"File e media" → "Consenti gestione di tutti i file"',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: () async {
                await openAppSettings();
              },
              icon: const Icon(Icons.settings),
              label: const Text('Apri Impostazioni'),
            ),
            const SizedBox(height: 12),
            TextButton(
              onPressed: _tryLoad,
              child: const Text('Ho attivato il permesso, ricontrolla'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    return ListView.builder(
      itemCount: items.length,
      itemBuilder: (context, i) {
        final item = items[i];
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
        );
      },
    );
  }

  Widget _buildGrid() {
    return GridView.builder(
      padding: const EdgeInsets.all(8),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        childAspectRatio: 0.85,
      ),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final item = items[i];
        final name = item.path.split('/').last;
        final isDir = item is Directory;

        return InkWell(
          onTap: () => _onItemTap(item, name, isDir),
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
}
