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

// Icone che devono seguire il tema (monocolore)
const Set<String> THEMED_ICONS = {
  'folder', 'folderAdd', 'search', 'settings', 'list', 'grid',
};

// Widget icona riusabile
class AppIcon extends StatelessWidget {
  final String name;
  final double size;
  const AppIcon(this.name, {super.key, this.size = 28});

  @override
  Widget build(BuildContext context) {
    final path = ICON_PATHS[name];
    if (path == null) return SizedBox(width: size, height: size);

    final isThemed = THEMED_ICONS.contains(name);
    final brightness = Theme.of(context).brightness;

    Widget img = Image.asset(path, width: size, height: size);

    if (isThemed) {
      // Applica filtro colore per adattare al tema
      final color = brightness == Brightness.dark ? Colors.white : Colors.black;
      img = ColorFiltered(
        colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
        child: img,
      );
    }

    return img;
  }
}

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

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  String currentPath = '/storage/emulated/0';
  List<FileSystemEntity> items = [];
  bool hasPermission = false;
  bool isGridView = false;

  @override
  void initState() {
    super.initState();
    _checkPermission();
  }

  Future<void> _checkPermission() async {
    var status = await Permission.manageExternalStorage.status;
    if (!status.isGranted) {
      status = await Permission.manageExternalStorage.request();
    }
    await Permission.photos.request();
    await Permission.videos.request();
    await Permission.audio.request();

    if (status.isGranted) {
      setState(() => hasPermission = true);
      _loadDirectory(currentPath);
    } else {
      setState(() => hasPermission = false);
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
        return a.path.split('/').last
            .toLowerCase()
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
    if (name.endsWith('.jpg') || name.endsWith('.png') || name.endsWith('.jpeg')
        || name.endsWith('.gif') || name.endsWith('.webp')) {
      return 'images';
    }
    if (name.endsWith('.mp4') || name.endsWith('.mkv') || name.endsWith('.avi')
        || name.endsWith('.mov')) {
      return 'video';
    }
    if (name.endsWith('.mp3') || name.endsWith('.wav') || name.endsWith('.ogg')
        || name.endsWith('.flac') || name.endsWith('.m4a')) {
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
    if (currentPath == '/storage/emulated/0') return;
    final parent = currentPath.substring(0, currentPath.lastIndexOf('/'));
    if (parent.length < '/storage/emulated/0'.length) return;
    _loadDirectory(parent);
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
            icon: AppIcon(isGridView ? 'list' : 'grid', size: 24),
            onPressed: () => setState(() => isGridView = !isGridView),
          ),
        ],
      ),
      body: !hasPermission
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'Permesso "Gestisci tutti i file" necessario.\n\n'
                  'Vai in: Impostazioni → App → File Manager → Autorizzazioni\n'
                  'e attiva "Gestisci tutti i file".',
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : items.isEmpty
              ? const Center(child: Text('Cartella vuota'))
              : isGridView
                  ? _buildGrid()
                  : _buildList(),
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

  void _onItemTap(FileSystemEntity item, String name, bool isDir) {
    if (isDir) {
      _loadDirectory(item.path);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Apro: $name')),
      );
    }
  }
}
