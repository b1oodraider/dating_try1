import 'package:flutter/material.dart';

import 'core/theme/app_theme.dart';
import 'features/auth/phone_screen.dart';
import 'features/auth/session_controller.dart';
import 'features/home/home_screen.dart';

class DatingApp extends StatefulWidget {
  const DatingApp({super.key, required this.session});

  final SessionController session;

  @override
  State<DatingApp> createState() => _DatingAppState();
}

class _DatingAppState extends State<DatingApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final GlobalKey<ScaffoldMessengerState> _messengerKey =
      GlobalKey<ScaffoldMessengerState>();

  @override
  void initState() {
    super.initState();
    widget.session.addListener(_onSessionChanged);
  }

  @override
  void dispose() {
    widget.session.removeListener(_onSessionChanged);
    super.dispose();
  }

  void _onSessionChanged() {
    // Sign-in / sign-out / expired token: drop pushed routes (e.g. the OTP
    // screen) so the root AuthGate is visible.
    _navigatorKey.currentState?.popUntil((route) => route.isFirst);

    final notice = widget.session.takeNotice();
    if (notice != null) {
      _messengerKey.currentState
        ?..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(notice)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Dating',
      navigatorKey: _navigatorKey,
      scaffoldMessengerKey: _messengerKey,
      theme: buildLightTheme(),
      darkTheme: buildDarkTheme(),
      themeMode: ThemeMode.system,
      home: AuthGate(session: widget.session),
    );
  }
}

/// Shows the login flow or the app depending on the session state.
class AuthGate extends StatelessWidget {
  const AuthGate({super.key, required this.session});

  final SessionController session;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: session,
      builder: (context, _) => session.isAuthenticated
          ? HomeScreen(session: session)
          : PhoneScreen(session: session),
    );
  }
}
