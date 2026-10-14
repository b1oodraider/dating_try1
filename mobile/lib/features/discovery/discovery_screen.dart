import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/public_profile.dart';
import '../auth/session_controller.dart';
import '../exclusion_words/exclusion_words_screen.dart';
import 'profile_card.dart';

/// Feed of published profiles with pull-to-refresh and load-more paging.
class DiscoveryScreen extends StatefulWidget {
  const DiscoveryScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<DiscoveryScreen> createState() => _DiscoveryScreenState();
}

class _DiscoveryScreenState extends State<DiscoveryScreen> {
  static const int _pageSize = 20;

  final ScrollController _scrollController = ScrollController();
  final List<PublicProfile> _items = <PublicProfile>[];

  int _page = 0;
  bool _hasMore = false;

  bool _loading = true; // first page, nothing to show yet
  String? _error; // first page failed and there is nothing to show
  bool _loadingMore = false;
  String? _loadMoreError;

  /// Bumped on every first-page load; stale responses are dropped.
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _loadFirstPage();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadFirstPage() async {
    final generation = ++_generation;
    try {
      final page = await widget.session.api
          .getDiscoveryProfiles(page: 0, limit: _pageSize);
      if (!mounted) return;
      if (generation != _generation) return;
      setState(() {
        _items
          ..clear()
          ..addAll(page.items);
        _page = page.page;
        _hasMore = page.hasMore;
        _loading = false;
        _error = null;
        _loadingMore = false;
        _loadMoreError = null;
      });
    } catch (e) {
      if (!mounted) return;
      if (generation != _generation) return;
      // On 401 the SessionController already switches to the login screen.
      if (e is ApiException && e.isUnauthorized) return;
      final message = describeError(e);
      if (_items.isNotEmpty) {
        // Keep showing the old list, just report the failed refresh.
        setState(() => _loadingMore = false);
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      } else {
        setState(() {
          _loading = false;
          _loadingMore = false;
          _error = message;
        });
      }
    }
  }

  void _retry() {
    setState(() {
      _loading = true;
      _error = null;
    });
    _loadFirstPage();
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || !_hasMore) return;
    final generation = _generation;
    setState(() {
      _loadingMore = true;
      _loadMoreError = null;
    });
    try {
      final page = await widget.session.api
          .getDiscoveryProfiles(page: _page + 1, limit: _pageSize);
      if (!mounted) return;
      if (generation != _generation) return;
      setState(() {
        _items.addAll(page.items);
        _page = page.page;
        _hasMore = page.hasMore;
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      if (generation != _generation) return;
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _loadingMore = false;
        _loadMoreError = describeError(e);
      });
    }
  }

  void _onScroll() {
    // Auto-load near the bottom; after a failure wait for an explicit retry.
    if (_loadMoreError != null) return;
    if (_scrollController.position.extentAfter < 600) _loadMore();
  }

  Future<void> _openExclusionWords() async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => ExclusionWordsScreen(session: widget.session),
      ),
    );
    if (!mounted) return;
    if (saved != true) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Слова-исключения сохранены')),
    );
    // The filter changed: start the feed over from the first page.
    setState(() {
      _items.clear();
      _page = 0;
      _hasMore = false;
      _loading = true;
      _error = null;
      _loadingMore = false;
      _loadMoreError = null;
    });
    await _loadFirstPage();
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Выйти из аккаунта?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Выйти'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await widget.session.signOut();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Анкеты'),
        actions: [
          IconButton(
            tooltip: 'Слова-исключения',
            icon: const Icon(Icons.block),
            onPressed: _openExclusionWords,
          ),
          IconButton(
            tooltip: 'Выйти',
            icon: const Icon(Icons.logout),
            onPressed: _confirmLogout,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    final error = _error;
    if (error != null) {
      return _ErrorView(message: error, onRetry: _retry);
    }

    if (_items.isEmpty) {
      return RefreshIndicator(
        onRefresh: _loadFirstPage,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: const [
            SizedBox(height: 160),
            Center(child: Text('Пока нет подходящих анкет')),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _loadFirstPage,
      child: ListView.builder(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(12),
        itemCount: _items.length + 1,
        itemBuilder: (context, index) {
          if (index == _items.length) return _buildFooter();
          final profile = _items[index];
          final photo = profile.primaryPhoto;
          return ProfileCard(
            profile: profile,
            photoUrl: photo == null
                ? null
                : widget.session.api.resolveMediaUrl(photo.url),
          );
        },
      ),
    );
  }

  Widget _buildFooter() {
    if (_loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    final loadMoreError = _loadMoreError;
    if (loadMoreError != null) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Text(loadMoreError, textAlign: TextAlign.center),
            TextButton(onPressed: _loadMore, child: const Text('Повторить')),
          ],
        ),
      );
    }
    if (_hasMore) {
      return Padding(
        padding: const EdgeInsets.all(8),
        child: Center(
          child: TextButton(
            onPressed: _loadMore,
            child: const Text('Показать ещё'),
          ),
        ),
      );
    }
    return const SizedBox(height: 24);
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton.tonal(
              onPressed: onRetry,
              child: const Text('Повторить'),
            ),
          ],
        ),
      ),
    );
  }
}
