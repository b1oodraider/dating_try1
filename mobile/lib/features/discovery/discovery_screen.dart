import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/account.dart';
import '../../core/models/discovery_filters.dart';
import '../../core/models/public_profile.dart';
import '../../core/models/wallet.dart';
import '../auth/session_controller.dart';
import '../collection/collection_screen.dart';
import '../profile/height_goals_screen.dart';
import '../testing/test_panel_screen.dart';
import 'discovery_filters_screen.dart';
import 'profile_card.dart';

/// Feed of published profiles with pull-to-refresh and load-more paging.
///
/// The query filters are saved per account and restored before the first
/// feed request. Height and goals (premium) stay saved while premium is
/// off but are not sent until it is back.
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

  /// The caller's account; decides whether the test panel is offered.
  Account? _account;

  /// Query filters as chosen and saved, including premium-only values.
  DiscoveryFilters _filters = DiscoveryFilters.none;

  /// True once [_filters] came from storage or from the user; storage is
  /// not read again after that.
  bool _filtersReady = false;

  /// Height and goals are not available now (wallet says so, or the
  /// server answered 403 PREMIUM_REQUIRED).
  bool _advancedLocked = false;

  /// What is actually sent: premium-only values are held back while locked.
  DiscoveryFilters get _effectiveFilters =>
      _advancedLocked ? _filters.withoutAdvanced() : _filters;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _start();
  }

  Future<void> _start() async {
    await _prepare();
    if (!mounted) return;
    await _loadFirstPage();
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
          .getDiscoveryPage(_effectiveFilters, page: 0, limit: _pageSize);
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
      if (_lockAdvancedFiltersOn(e)) {
        await _loadFirstPage();
        return;
      }
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

  /// Loads the account (test panel, key of the saved filters), the saved
  /// filters and the premium state. Best effort: on failure the feed is
  /// loaded with what is known (the server enforces premium anyway).
  Future<void> _prepare() async {
    final api = widget.session.api;
    final walletFuture = api.getWallet().then<Wallet?>(
      (wallet) => wallet,
      onError: (Object e) {
        debugPrint('Failed to load wallet: $e');
        return null;
      },
    );

    if (_account == null) {
      try {
        final account = await api.getMe();
        if (!mounted) return;
        setState(() => _account = account);
      } catch (e) {
        if (!mounted) return;
        debugPrint('Failed to load account: $e');
      }
    }

    final account = _account;
    if (account != null && !_filtersReady) {
      final saved = await widget.session.filtersStore.load(account.id);
      if (!mounted) return;
      if (!_filtersReady) {
        setState(() {
          _filters = saved;
          _filtersReady = true;
        });
      }
    }

    final wallet = await walletFuture;
    if (!mounted) return;
    if (wallet != null) {
      // Premium may have come back: saved height / goals apply again.
      setState(() {
        _advancedLocked =
            wallet.isFeatureLocked(PremiumFeatureCodes.advancedFilters);
      });
    }
  }

  Future<void> _refresh() async {
    await _prepare();
    if (!mounted) return;
    await _loadFirstPage();
  }

  void _retry() {
    setState(() {
      _loading = true;
      _error = null;
    });
    _refresh();
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
          .getDiscoveryPage(
            _effectiveFilters,
            page: _page + 1,
            limit: _pageSize,
          );
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
      if (_lockAdvancedFiltersOn(e)) {
        await _restartFeed(refreshState: false);
        return;
      }
      setState(() {
        _loadingMore = false;
        _loadMoreError = describeError(e);
      });
    }
  }

  /// Premium ended while height / goal filters were sent (403
  /// PREMIUM_REQUIRED): keep them saved but stop sending them, tell the
  /// user and return true so the caller reloads the feed.
  bool _lockAdvancedFiltersOn(Object error) {
    if (error is! ApiException || !error.isPremiumRequired) return false;
    if (!_effectiveFilters.hasAdvanced) return false;
    setState(() => _advancedLocked = true);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text(
          'Фильтры по росту и целям сохранены, '
          'но не применяются без премиума',
        ),
      ),
    );
    return true;
  }

  /// Starts the feed over from the first page (filters changed). With
  /// [refreshState] the premium state is re-read first.
  Future<void> _restartFeed({bool refreshState = true}) async {
    setState(() {
      _items.clear();
      _page = 0;
      _hasMore = false;
      _loading = true;
      _error = null;
      _loadingMore = false;
      _loadMoreError = null;
    });
    if (refreshState) {
      await _prepare();
      if (!mounted) return;
    }
    await _loadFirstPage();
  }

  /// Remembers [filters] (also on disk for the current account).
  void _setFilters(DiscoveryFilters filters) {
    setState(() {
      _filters = filters;
      _filtersReady = true;
    });
    final account = _account;
    if (account != null) {
      widget.session.filtersStore.save(account.id, filters);
    }
  }

  Future<void> _openFilters() async {
    var savedFiltersChanged = false;
    final result = await Navigator.of(context).push<DiscoveryFilters>(
      MaterialPageRoute<DiscoveryFilters>(
        builder: (_) => DiscoveryFiltersScreen(
          session: widget.session,
          initial: _filters,
          onSavedFiltersChanged: () => savedFiltersChanged = true,
        ),
      ),
    );
    if (!mounted) return;
    if (result == null && !savedFiltersChanged) return;
    if (result != null) _setFilters(result);
    await _restartFeed();
  }

  /// Resets what is applied now; saved premium values that are held back
  /// while premium is locked are kept.
  void _resetFilters() {
    _setFilters(
      _advancedLocked ? _filters.onlyAdvanced() : DiscoveryFilters.none,
    );
    _restartFeed();
  }

  void _onScroll() {
    // Auto-load near the bottom; after a failure wait for an explicit retry.
    if (_loadMoreError != null) return;
    if (_scrollController.position.extentAfter < 600) _loadMore();
  }

  Future<void> _openHeightGoals() async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => HeightGoalsScreen(session: widget.session),
      ),
    );
    if (!mounted) return;
    if (saved != true) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Рост и цели сохранены')),
    );
  }

  void _openCollection() {
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => CollectionScreen(session: widget.session),
      ),
    );
  }

  void _openTestPanel() {
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => TestPanelScreen(session: widget.session),
      ),
    );
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
            tooltip: 'Фильтры',
            icon: Badge(
              isLabelVisible: !_effectiveFilters.isEmpty,
              smallSize: 8,
              child: const Icon(Icons.tune),
            ),
            onPressed: _openFilters,
          ),
          IconButton(
            tooltip: 'Рост и цели',
            icon: const Icon(Icons.person_outline),
            onPressed: _openHeightGoals,
          ),
          IconButton(
            tooltip: 'Коллекция',
            icon: const Icon(Icons.collections_bookmark_outlined),
            onPressed: _openCollection,
          ),
          if (_account?.testAccount ?? false)
            IconButton(
              tooltip: 'Тест-панель',
              icon: const Icon(Icons.science_outlined),
              onPressed: _openTestPanel,
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
        onRefresh: _refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 160),
            const Center(child: Text('Пока нет подходящих анкет')),
            if (!_effectiveFilters.isEmpty) ...[
              const SizedBox(height: 12),
              Center(
                child: TextButton(
                  onPressed: _resetFilters,
                  child: const Text('Сбросить фильтры'),
                ),
              ),
            ],
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _refresh,
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
