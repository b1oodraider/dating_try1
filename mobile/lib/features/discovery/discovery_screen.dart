import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/widgets/empty_state.dart';
import '../../core/models/account.dart';
import '../../core/models/community.dart';
import '../../core/models/discovery_filters.dart';
import '../../core/models/public_profile.dart';
import '../../core/models/social.dart';
import '../../core/models/wallet.dart';
import '../auth/session_controller.dart';
import '../chat/match_dialog.dart';
import '../safety/verification_screen.dart';
import '../premium/purchase_screen.dart';
import 'boost_button.dart';
import 'discovery_filters_screen.dart';
import 'profile_details_sheet.dart';
import 'swipe_deck.dart';

/// The feed as a Tinder-style deck of cards.
///
/// Reactions take the card off at once; the request runs in the background
/// and a failure puts the card back. More cards are fetched from page 0 again
/// (the server hides every profile I reacted to, so offset paging would skip
/// some) once the reactions in flight are done.
///
/// The query filters are saved per account and restored before the first
/// feed request. Height and goals (premium) stay saved while premium is
/// off but are not sent until it is back.
class DiscoveryScreen extends StatefulWidget {
  const DiscoveryScreen({
    super.key,
    required this.session,
    this.onOpenChat,
    this.onOpenProfile,
    this.groupSearch,
  });

  final SessionController session;

  /// «Начать поиск» on a group page: the feed is set to that group (and the value is taken back to null).
  final ValueNotifier<Community?>? groupSearch;

  /// Opens the chat of a new match (from the "It's a match" dialog).
  final void Function(String matchId)? onOpenChat;

  /// The feed needs my gender, who I look for and birth date first.
  final VoidCallback? onOpenProfile;

  @override
  State<DiscoveryScreen> createState() => _DiscoveryScreenState();
}

class _DiscoveryScreenState extends State<DiscoveryScreen> {
  static const int _pageSize = 20;
  static const int _refillAt = 5;
  static const int _viewsBatch = 10;

  final List<PublicProfile> _items = <PublicProfile>[];

  /// The feed refused: my gender, who I look for or birth date are missing.
  bool _needsProfile = false;

  /// Unverified and the daily views are used up (429 DAILY_VIEWS_LIMIT).
  String? _verifyToSeeMore;

  /// Active LIKES restriction (from GET /me/restrictions or a 403 ACCOUNT_RESTRICTED).
  Restriction? _likesRestriction;

  /// The «your like waits for verification» hint was shown in this session.
  bool _queuedHintShown = false;

  /// The server had nothing beyond the cards already in the deck.
  bool _exhausted = false;

  /// Reactions in flight; a refill waits for them.
  int _pending = 0;

  /// Reacted to in this session: never shown again even if a response races.
  final Set<String> _reacted = <String>{};

  /// When the current top card appeared and whether its details were opened.
  String? _viewId;
  DateTime _viewStart = DateTime.now();
  bool _viewOpened = false;
  final List<Map<String, Object>> _views = <Map<String, Object>>[];

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

  /// Titles of the groups searched among, for the bar above the feed.
  final Map<int, String> _groupNames = {};

  @override
  void initState() {
    super.initState();
    widget.groupSearch?.addListener(_onGroupSearch);
    _start();
  }

  void _onGroupSearch() {
    final group = widget.groupSearch?.value;
    if (group == null) return;
    widget.groupSearch!.value = null;
    _groupNames[group.id] = group.display;
    _setFilters(_filters.withCommunities([group.id]));
    _restartFeed();
  }

  /// Names for group ids that came from storage or the filters.
  Future<void> _loadGroupNames() async {
    for (final id in _filters.communityIds.where((id) => !_groupNames.containsKey(id)).toList()) {
      try {
        final group = await widget.session.api.getCommunity(id);
        if (mounted) setState(() => _groupNames[id] = group.display);
      } catch (e) {
        debugPrint('Group $id: $e');
      }
    }
  }

  void _stopGroupSearch() {
    _setFilters(_filters.withCommunities(const <int>[]));
    _restartFeed();
  }

  Future<void> _start() async {
    _loadRestrictions();
    await _prepare();
    if (!mounted) return;
    await _loadFirstPage();
  }

  /// Best effort: on failure the server still refuses likes with 403 ACCOUNT_RESTRICTED.
  Future<void> _loadRestrictions() async {
    try {
      final all = await widget.session.api.getRestrictions();
      if (!mounted) return;
      setState(() => _likesRestriction =
          all.where((r) => r.kind == 'LIKES' || r.kind == 'SUSPENDED').firstOrNull);
    } catch (e) {
      debugPrint('Failed to load restrictions: $e');
    }
  }

  void _showLikesBlocked() {
    final r = _likesRestriction;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(r == null ? 'Лайки недоступны' : _restrictionText(r))));
  }

  static String _restrictionText(Restriction r) {
    final until = r.endsAt;
    return until == null
        ? 'Лайки недоступны до решения модератора'
        : 'Лайки недоступны до ${formatDateTime(until)}';
  }

  @override
  void dispose() {
    widget.groupSearch?.removeListener(_onGroupSearch);
    _flushViews();
    super.dispose();
  }

  Future<void> _loadFirstPage() async {
    final generation = ++_generation;
    try {
      final page = await widget.session.api
          .getDiscoveryPage(_effectiveFilters, page: 0, limit: _pageSize);
      if (!mounted) return;
      if (generation != _generation) return;
      final fresh = page.items.where((p) => !_reacted.contains(p.id)).toList();
      setState(() {
        _items
          ..clear()
          ..addAll(fresh);
        _exhausted = page.totalElements <= fresh.length;
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
      if (_dailyLimitOn(e)) return;
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
          _needsProfile = e is ApiException && e.code == 'PROFILE_INCOMPLETE';
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
        _loadGroupNames();
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

  void _maybeRefill() {
    if (_items.length > _refillAt || _exhausted || _pending > 0) return;
    if (_loading || _loadingMore || _loadMoreError != null || _error != null) {
      return;
    }
    _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore) return;
    final generation = _generation;
    setState(() {
      _loadingMore = true;
      _loadMoreError = null;
    });
    try {
      final page = await widget.session.api
          .getDiscoveryPage(_effectiveFilters, page: 0, limit: _pageSize);
      if (!mounted) return;
      if (generation != _generation) return;
      final known = {..._items.map((p) => p.id), ..._reacted};
      final added = page.items.where((p) => !known.contains(p.id)).toList();
      setState(() {
        _items.addAll(added);
        _exhausted = added.isEmpty;
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      if (generation != _generation) return;
      if (e is ApiException && e.isUnauthorized) return;
      if (_dailyLimitOn(e)) return;
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
      SnackBar(
        content: const Text(
          'Фильтры по росту и целям сохранены, '
          'но не применяются без премиума',
        ),
        action: SnackBarAction(
          label: 'Премиум',
          onPressed: () async {
            final bought = await openPurchase(context, api: widget.session.api, kind: PurchaseKind.premium);
            if (bought && mounted) _restartFeed();
          },
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
      _exhausted = false;
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
    _loadGroupNames();
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

  // ------------------------------------------------- views (like-spam rule)

  void _trackTop() {
    final top = _items.isEmpty ? null : _items.first.id;
    if (top == _viewId) return;
    _viewId = top;
    _viewStart = DateTime.now();
    _viewOpened = false;
  }

  void _recordView(String profileId) {
    if (_viewId != profileId) return;
    final dwell = DateTime.now().difference(_viewStart).inMilliseconds;
    _views.add({
      'profileId': profileId,
      'dwellMs': math.min(dwell, 3600000),
      'openedDetails': _viewOpened,
    });
    _viewId = null;
    if (_views.length >= _viewsBatch) _flushViews();
  }

  void _flushViews() {
    if (_views.isEmpty) return;
    final batch = List<Map<String, Object>>.of(_views);
    _views.clear();
    widget.session.api.sendProfileViews(batch).catchError((Object e) {
      debugPrint('Views not sent: $e');
    });
  }

  // ---------------------------------------------------------- reactions

  Future<void> _react(PublicProfile profile, SwipeAction action) async {
    _recordView(profile.id);
    setState(() {
      _reacted.add(profile.id);
      _items.removeWhere((p) => p.id == profile.id);
      _pending++;
    });
    final api = widget.session.api;
    try {
      final result = await switch (action) {
        SwipeAction.like => api.like(profile.id),
        SwipeAction.superlike =>
          api.superlike(profile.id, _newKey()),
        SwipeAction.pass => api.pass(profile.id),
      };
      if (!mounted) return;
      if (result.queued && !_queuedHintShown) {
        _queuedHintShown = true;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: const Text('Лайк отправится, когда подтвердите селфи'),
          action: SnackBarAction(label: 'Подтвердить', onPressed: _openVerification),
        ));
      }
      final matchId = result.matchId;
      if (result.matched && matchId != null) {
        showMatchDialog(
          context,
          profile: profile,
          photoUrl: api.resolveMediaUrl,
          onOpenChat: () => widget.onOpenChat?.call(matchId),
        );
      }
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      if (e is ApiException && e.code == 'ACCOUNT_RESTRICTED') _loadRestrictions();
      // A profile that is gone (hidden, blocked) is not put back.
      if (!(e is ApiException && e.code == 'PROFILE_NOT_FOUND')) {
        setState(() {
          _reacted.remove(profile.id);
          _items.insert(0, profile);
        });
      }
      if (e is ApiException && e.code == 'INSUFFICIENT_BALANCE') {
        // Out of super likes: the card is back on top; the shop opens over the feed.
        openPurchase(context, api: widget.session.api, kind: PurchaseKind.superlikes);
      } else {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(describeError(e))));
      }
    } finally {
      if (mounted) {
        setState(() => _pending--);
        _maybeRefill();
      }
    }
  }

  /// Premium: puts my last swiped profile back on top of the deck.
  Future<void> _undo() async {
    try {
      final profile = await widget.session.api.undoLastReaction();
      if (!mounted) return;
      setState(() {
        _reacted.remove(profile.id);
        _items.removeWhere((p) => p.id == profile.id);
        _items.insert(0, profile);
      });
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      if (e is ApiException && e.isPremiumRequired) {
        final bought = await openPurchase(context, api: widget.session.api, kind: PurchaseKind.premium);
        if (bought && mounted) _undo();
        return;
      }
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(describeError(e))));
    }
  }

  /// Brings passed profiles back (free: when the feed ran out, once a day).
  Future<void> _refreshPasses() async {
    try {
      final restored = await widget.session.api
          .refreshPasses(feedEmpty: _items.isEmpty);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(restored == 0
              ? 'Пропущенных анкет нет'
              : 'Вернули пропущенных: $restored')));
      _reacted.clear(); // likes stay excluded by the server
      await _restartFeed();
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      // Without premium: once a day and only when the feed ran out; premium lifts both.
      if (e is ApiException &&
          (e.isPremiumRequired || e.code == 'PASS_REFRESH_TOO_SOON' || e.code == 'FEED_NOT_EMPTY')) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
        final bought = await openPurchase(context, api: widget.session.api, kind: PurchaseKind.premium);
        if (bought && mounted) _refreshPasses();
        return;
      }
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(describeError(e))));
    }
  }

  void _openVerification() {
    Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => VerificationScreen(api: widget.session.api)));
  }

  /// True when [error] is the daily limit of an unverified account.
  bool _dailyLimitOn(Object error) {
    if (error is! ApiException || error.code != 'DAILY_VIEWS_LIMIT') return false;
    setState(() {
      _verifyToSeeMore = error.message;
      _loading = false;
      _loadingMore = false;
    });
    return true;
  }

  static String _newKey() =>
      '${DateTime.now().microsecondsSinceEpoch}-${math.Random().nextInt(1 << 32)}';

  Future<void> _openDetails(PublicProfile profile) async {
    if (_viewId == profile.id) _viewOpened = true;
    final result = await showProfileDetails(
      context,
      api: widget.session.api,
      profile: profile,
      withReactions: true,
    );
    if (!mounted || result == null) return;
    if (result == ProfileDetailsResult.blocked ||
        result == ProfileDetailsResult.hidden) {
      setState(() {
        _reacted.add(profile.id);
        _items.removeWhere((p) => p.id == profile.id);
      });
      _maybeRefill();
      return;
    }
    _deckKey.currentState?.swipe(switch (result) {
      ProfileDetailsResult.like => SwipeAction.like,
      ProfileDetailsResult.superlike => SwipeAction.superlike,
      _ => SwipeAction.pass,
    });
  }

  final GlobalKey<SwipeDeckState> _deckKey = GlobalKey<SwipeDeckState>();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          tooltip: 'Вернуть последнюю анкету',
          icon: const Icon(Icons.replay_rounded),
          onPressed: _undo,
        ),
        title: const Text('Анкеты'),
        actions: [
          BoostButton(api: widget.session.api),
          IconButton(
            tooltip: 'Фильтры',
            icon: Badge(
              isLabelVisible: !_effectiveFilters.isEmpty,
              smallSize: 8,
              child: const Icon(Icons.tune),
            ),
            onPressed: _openFilters,
          ),
        ],
      ),
      body: _filters.communityIds.isEmpty
          ? _buildBody()
          : Column(
              children: [
                _GroupSearchBar(
                  names: [for (final id in _filters.communityIds) _groupNames[id] ?? 'группа'],
                  onClose: _stopGroupSearch,
                ),
                Expanded(child: _buildBody()),
              ],
            ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    final error = _error;
    if (error != null) {
      if (_needsProfile) {
        return EmptyState(
          icon: Icons.person_search_rounded,
          title: 'Почти готово',
          message: error,
          actions: [
            FilledButton(
              onPressed: widget.onOpenProfile,
              child: const Text('Заполнить анкету'),
            ),
            TextButton(onPressed: _retry, child: const Text('Обновить')),
          ],
        );
      }
      return _ErrorView(message: error, onRetry: _retry);
    }

    _trackTop();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _maybeRefill();
    });

    final verifyToSeeMore = _verifyToSeeMore;
    if (_items.isEmpty && verifyToSeeMore != null) {
      return EmptyState(
        icon: Icons.verified_user_outlined,
        title: 'На сегодня всё',
        message: verifyToSeeMore,
        actions: [
          FilledButton(
            onPressed: _openVerification,
            child: const Text('Подтвердить селфи'),
          ),
          TextButton(
            onPressed: () {
              setState(() => _verifyToSeeMore = null);
              _retry();
            },
            child: const Text('Обновить'),
          ),
        ],
      );
    }

    if (_items.isEmpty) {
      final loadMoreError = _loadMoreError;
      if (loadMoreError != null) {
        return _ErrorView(message: loadMoreError, onRetry: _loadMore);
      }
      if (_loadingMore || _pending > 0) {
        return const Center(child: CircularProgressIndicator());
      }
      return EmptyState(
        icon: Icons.travel_explore_rounded,
        title: _reacted.isEmpty ? 'Пока нет подходящих анкет' : 'Вы посмотрели все анкеты',
        message: _effectiveFilters.isEmpty
            ? 'Загляните позже — новые люди появляются каждый день.'
            : 'Попробуйте ослабить фильтры.',
        actions: [
          FilledButton(onPressed: _retry, child: const Text('Обновить')),
          OutlinedButton(
            onPressed: _refreshPasses,
            child: const Text('Показать пропущенных снова'),
          ),
          if (!_effectiveFilters.isEmpty)
            TextButton(
              onPressed: _resetFilters,
              child: const Text('Сбросить фильтры'),
            ),
        ],
      );
    }

    final likesRestriction = _likesRestriction;
    final deck = SwipeDeck(
      key: _deckKey,
      profiles: _items,
      photoUrl: widget.session.api.resolveMediaUrl,
      onSwipe: _react,
      onOpenDetails: _openDetails,
      likesBlocked: likesRestriction != null,
      onLikeBlocked: _showLikesBlocked,
    );
    if (likesRestriction == null) return deck;
    return Column(
      children: [
        MaterialBanner(
          leading: const Icon(Icons.block),
          content: Text('${_restrictionText(likesRestriction)}. ${likesRestriction.reason}'.trim()),
          actions: [TextButton(onPressed: _loadRestrictions, child: const Text('Обновить'))],
        ),
        Expanded(child: deck),
      ],
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => EmptyState(
        icon: Icons.wifi_off_rounded,
        title: 'Не удалось загрузить',
        message: message,
        actions: [FilledButton(onPressed: onRetry, child: const Text('Повторить'))],
      );
}

/// «Поиск по группе: ⚡ Гарри Поттер ✕» above the feed.
class _GroupSearchBar extends StatelessWidget {
  const _GroupSearchBar({required this.names, required this.onClose});

  final List<String> names;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 4, 4),
        child: Row(
          children: [
            const Icon(Icons.groups_outlined, size: 20),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${names.length == 1 ? 'Поиск по группе' : 'Поиск по группам'}: ${names.join(', ')}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onPrimaryContainer),
              ),
            ),
            IconButton(tooltip: 'Искать среди всех', icon: const Icon(Icons.close_rounded), onPressed: onClose),
          ],
        ),
      ),
    );
  }
}
