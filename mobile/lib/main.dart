import 'package:flutter/widgets.dart';

import 'app.dart';
import 'core/api/api_client.dart';
import 'core/storage/token_storage.dart';
import 'features/auth/session_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final session = SessionController(
    api: DatingApiClient(),
    storage: TokenStorage(),
  );
  await session.restore();

  runApp(DatingApp(session: session));
}
