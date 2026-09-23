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

  @override
  void initState() {
    super.initState();
    _loadDirectory(ROOT);
  }

  void _loadDirectory(String path) {
    List<FileSystemEntity> list = [];
    try {
      final dir = Directory(path);
      if (dir.existsSync()) {
        list = dir.listSync();
        list.sort((a, b) {
          final aIsDir = a is Directory;
          final bIsDir = b is Directory;
          if (aIsDir && !bIsDir) return -1;
          if (!aIsDir && bIsDir) return 1;
          return a.path.split('/').last.toLowerCase()
              .compareTo(b.path.split('/').last.toLowerCase());
        });
      }
    } catch (_) {
      list = [];
    }
    setState(() {
      currentPath = path;
      items = list;
    });
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
            onPressed: () => _loadDirectory(currentPath),
          ),
        ],
      ),
      body: items.isEmpty
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
}
