import 'package:flutter/material.dart';

import '../../core/api/realtime.dart';
import '../auth/session_controller.dart';
import '../chat/chat_screen.dart';
import '../chat/chats_controller.dart';
import '../chat/chats_screen.dart';
import '../discovery/discovery_screen.dart';
import '../gacha/gacha_screen.dart';
import '../likes/likes_screen.dart';
import '../profile/my_profile_screen.dart';
import '../profile/profile_wizard.dart';
import '../update/update_checker.dart';

/// The signed-in app: feed, likes, chats, gacha, my profile. Owns the event stream
/// and the match list for the whole session.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final RealtimeConnection _realtime =
      RealtimeConnection(widget.session.api);
  late final ChatsController _chats =
      ChatsController(widget.session.api, _realtime.events);
  final GlobalKey<NavigatorState> _chatsNavigator = GlobalKey<NavigatorState>();
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    _chats.load();
    _realtime.start();
    WidgetsBinding.instance.addPostFrameCallback((_) => _firstRun());
  }

  /// A new person gets the onboarding (intro + profile wizard); everyone gets a quiet update check.
  Future<void> _firstRun() async {
    final api = widget.session.api;
    try {
      final profile = await api.getMyProfile();
      if (mounted && profile.publicationStatus == 'DRAFT') {
        final published = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
          fullscreenDialog: true,
          builder: (_) => OnboardingScreen(api: api, profile: profile),
        ));
        if (mounted && published == true) setState(() => _tab = 0);
      }
    } catch (e) {
      debugPrint('Onboarding check failed: $e');
    }
    try {
      final update = await checkForUpdate(api);
      if (mounted && update != null) await showUpdateDialog(context, update);
    } catch (e) {
      debugPrint('Update check failed: $e'); // offline or no version file: try next start
    }
  }

  @override
  void dispose() {
    _chats.dispose();
    _realtime.close();
    super.dispose();
  }

  Future<void> _openChat(String matchId) async {
    setState(() => _tab = 2);
    if (_chats.byId(matchId) == null) await _chats.load();
    _chatsNavigator.currentState?.push<void>(MaterialPageRoute<void>(
      builder: (_) => ChatScreen(
        controller: _chats,
        events: _realtime.events,
        matchId: matchId,
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final api = widget.session.api;
    return Scaffold(
      body: IndexedStack(
        index: _tab,
        children: [
          DiscoveryScreen(
            session: widget.session,
            onOpenChat: _openChat,
            onOpenProfile: () => setState(() => _tab = 4),
          ),
          LikesScreen(api: api, events: _realtime.events, onOpenChat: _openChat),
          // Own navigator: the bottom bar stays visible inside a chat.
          Navigator(
            key: _chatsNavigator,
            onGenerateRoute: (_) => MaterialPageRoute<void>(
              builder: (_) =>
                  ChatsScreen(controller: _chats, events: _realtime.events),
            ),
          ),
          GachaScreen(api: api),
          MyProfileScreen(session: widget.session),
        ],
      ),
      bottomNavigationBar: ListenableBuilder(
        listenable: _chats,
        builder: (context, _) => NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (index) => setState(() => _tab = index),
          destinations: [
            const NavigationDestination(
              icon: Icon(Icons.style_outlined),
              selectedIcon: Icon(Icons.style),
              label: 'Анкеты',
            ),
            const NavigationDestination(
              icon: Icon(Icons.favorite_border),
              selectedIcon: Icon(Icons.favorite),
              label: 'Симпатии',
            ),
            NavigationDestination(
              icon: Badge(
                isLabelVisible: _chats.unreadChats > 0,
                label: Text('${_chats.unreadChats}'),
                child: const Icon(Icons.chat_bubble_outline),
              ),
              selectedIcon: const Icon(Icons.chat_bubble),
              label: 'Чаты',
            ),
            const NavigationDestination(
              icon: Icon(Icons.casino_outlined),
              selectedIcon: Icon(Icons.casino),
              label: 'Удача',
            ),
            const NavigationDestination(
              icon: Icon(Icons.person_outline),
              selectedIcon: Icon(Icons.person),
              label: 'Профиль',
            ),
          ],
        ),
      ),
    );
  }
}
