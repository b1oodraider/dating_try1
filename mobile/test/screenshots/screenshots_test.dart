// Renders the main screens to PNGs with a fake backend (no emulator, no server).
//
//   SCREENSHOT_DIR=/some/dir flutter test test/screenshots
//
// Without SCREENSHOT_DIR every test is skipped. Photos come from
// SCREENSHOT_PHOTOS (<dir>/<folder>/<n>.png); missing files get a gradient.
import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/models/auth.dart';
import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/storage/token_storage.dart';
import 'package:dating_mobile/core/theme/app_theme.dart';
import 'package:dating_mobile/features/auth/otp_screen.dart';
import 'package:dating_mobile/features/auth/phone_screen.dart';
import 'package:dating_mobile/features/auth/session_controller.dart';
import 'package:dating_mobile/core/models/date_plan.dart';
import 'package:dating_mobile/core/models/discovery_filters.dart';
import 'package:dating_mobile/features/dates/date_plan_editor.dart';
import 'package:dating_mobile/features/premium/purchase_screen.dart';
import 'package:dating_mobile/features/discovery/discovery_filters_screen.dart';
import 'package:dating_mobile/features/discovery/discovery_screen.dart';
import 'package:dating_mobile/core/models/my_profile.dart';
import 'package:dating_mobile/features/discovery/profile_card.dart';
import 'package:dating_mobile/features/discovery/profile_details_sheet.dart';
import 'package:dating_mobile/features/discovery/profile_preview.dart';
import 'package:dating_mobile/features/testing/test_panel_screen.dart';
import 'package:dating_mobile/features/home/home_screen.dart';
import 'package:dating_mobile/features/profile/profile_section_screen.dart';
import 'package:dating_mobile/features/profile/profile_steps.dart';
import 'package:dating_mobile/features/profile/profile_wizard.dart';
import 'package:dating_mobile/features/support/support_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fixtures.dart' as fx;

final String? _outDir = Platform.environment['SCREENSHOT_DIR'];
final String _photosDir = Platform.environment['SCREENSHOT_PHOTOS'] ??
    r'C:\Users\dno22\AppData\Local\Temp\claude\C--Users-dno22-OneDrive-Desktop-dating-prod-dating-try1--claude-worktrees-auth-security-fixes-f5ea4a\4ae9853a-db6d-41f7-80a5-ffc134632724\scratchpad\seed2\photos';

/// The one place the harness couples to the app theme.
Widget _app(Widget home, {bool dark = false}) => MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: buildLightTheme(),
      darkTheme: buildDarkTheme(),
      themeMode: dark ? ThemeMode.dark : ThemeMode.light,
      home: home,
    );

final GlobalKey _boundary = GlobalKey();

SessionController _session() {
  final api = DatingApiClient(
    baseUrl: fx.baseUrl,
    httpClient: MockClient((request) async => fx.respond(request)),
  )..token = 'screenshot-token';
  return SessionController(api: api, storage: TokenStorage());
}

Future<void> _loadFonts() async {
  final sdk = Platform.environment['FLUTTER_ROOT'] ?? r'C:\src\flutter';
  final dir = Directory('$sdk/bin/cache/artifacts/material_fonts');
  final roboto = FontLoader('Roboto');
  for (final file in dir.listSync().whereType<File>()) {
    final name = file.uri.pathSegments.last;
    if (name.startsWith('roboto-') && name.endsWith('.ttf')) {
      roboto.addFont(file.readAsBytes().then(ByteData.sublistView));
    }
  }
  await roboto.load();
  final onest = FontLoader('Onest');
  for (final weight in ['Regular', 'Medium', 'SemiBold', 'Bold']) {
    onest.addFont(File('assets/fonts/Onest-$weight.ttf').readAsBytes().then(ByteData.sublistView));
  }
  await onest.load();
  await (FontLoader('MaterialIcons')
        ..addFont(File('${dir.path}/materialicons-regular.otf')
            .readAsBytes()
            .then(ByteData.sublistView)))
      .load();
}

/// Mounts [home] at 390x844 @2x with fake network images.
Future<void> _pump(WidgetTester tester, Widget home,
    {bool dark = false}) async {
  HttpOverrides.global = _ImageHttpOverrides();
  // Overflow stripes are what a screenshot should show: log, don't fail.
  final reportError = FlutterError.onError;
  FlutterError.onError = (details) {
    final text = details.exceptionAsString();
    if (text.contains('overflowed')) {
      debugPrint('LAYOUT OVERFLOW (${tester.testDescription}): $text');
    } else {
      reportError?.call(details);
    }
  };
  addTearDown(() => FlutterError.onError = reportError);
  tester.view.physicalSize = const Size(780, 1688);
  tester.view.devicePixelRatio = 2;
  if (dark) {
    tester.platformDispatcher.platformBrightnessTestValue = ui.Brightness.dark;
  }
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
  await tester.pumpWidget(
      RepaintBoundary(key: _boundary, child: _app(home, dark: dark)));
  await _settle(tester);
}

/// Lets requests finish and images decode (needs real async), until no
/// progress indicator is left.
Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 100));
    await tester.runAsync(() async {
      for (final element in find.byType(Image).evaluate().toList()) {
        await precacheImage((element.widget as Image).image, element);
      }
    });
    await tester.pump(const Duration(milliseconds: 400));
  }
  await tester.pump(const Duration(seconds: 1));
}

Future<void> _shot(WidgetTester tester, String name) async {
  await tester.runAsync(() async {
    final boundary =
        _boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 2);
    final png = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    final file = File('$_outDir/$name.png');
    await file.parent.create(recursive: true);
    await file.writeAsBytes(png!.buffer.asUint8List());
  });
}

/// The visible main list: the vertical scrollable with the most content.
ScrollPosition _mainScrollPosition(WidgetTester tester) => find
    .byType(Scrollable)
    .evaluate()
    .map((e) => (e as StatefulElement).state as ScrollableState)
    .where((s) => s.position.axis == Axis.vertical)
    .map((s) => s.position)
    .reduce((a, b) => a.maxScrollExtent >= b.maxScrollExtent ? a : b);

/// Scrolls the open bottom sheet's list (the last vertical scrollable) to [offset].
void _scrollSheet(WidgetTester tester, double offset) {
  final position = find
      .byType(Scrollable)
      .evaluate()
      .map((e) => (e as StatefulElement).state as ScrollableState)
      .where((s) => s.position.axis == Axis.vertical)
      .last
      .position;
  position.jumpTo(offset.clamp(0, position.maxScrollExtent));
}

Future<void> _tapTab(WidgetTester tester, String label) async {
  await tester.tap(find.descendant(
      of: find.byType(NavigationBar), matching: find.text(label)));
  await _settle(tester);
}

Future<void> _feed(WidgetTester tester, {bool dark = false}) async {
  await _pump(tester, HomeScreen(session: _session()), dark: dark);
}

void main() {
  final skip = _outDir == null;

  setUpAll(() async {
    if (skip) return;
    SharedPreferences.setMockInitialValues({});
    // flutter_map caches tiles under the app cache directory: give it a temp one.
    final cache = Directory.systemTemp.createTempSync('screenshots-cache').path;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('plugins.flutter.io/path_provider'), (call) async => cache);
    await _loadFonts();
  });

  setUp(Dictionaries.reset);

  // Unmount inside the test: HomeScreen's dispose closes the realtime stream.
  tearDown(() => HttpOverrides.global = null);

  testWidgets('phone', skip: skip, (tester) async {
    await _pump(tester, PhoneScreen(session: _session()));
    await _shot(tester, 'phone');
  });

  testWidgets('otp', skip: skip, (tester) async {
    await _pump(
      tester,
      OtpScreen(
        session: _session(),
        phone: '+79161234567',
        challenge: OtpChallenge(
          challengeId: 'ch1',
          expiresAt: DateTime.now().add(const Duration(minutes: 5)),
          maskedPhone: '+7 *** ***-45-67',
          devMockCode: null,
        ),
      ),
    );
    await _shot(tester, 'otp');
  });

  testWidgets('feed', skip: skip, (tester) async {
    await _feed(tester);
    await _shot(tester, 'feed');
  });

  testWidgets('details', skip: skip, (tester) async {
    final session = _session();
    await _pump(tester, HomeScreen(session: session));
    unawaited(showProfileDetails(
      tester.element(find.byType(DiscoveryScreen)),
      api: session.api,
      profile: PublicProfile.fromJson(fx.feed.first),
      withReactions: true,
    ));
    await _settle(tester);
    await _shot(tester, 'details');
    await tester.tap(find.byType(PhotoCarousel), warnIfMissed: false);
    await _settle(tester);
    _scrollSheet(tester, 520);
    await _settle(tester);
    await _shot(tester, 'details_scrolled');
    _scrollSheet(tester, 2000);
    await _settle(tester);
    await _shot(tester, 'details_bottom');
  });

  testWidgets('my profile as others', skip: skip, (tester) async {
    final session = _session();
    await _pump(tester, HomeScreen(session: session));
    unawaited(showMyProfileAsOthers(
      tester.element(find.byType(DiscoveryScreen)),
      api: session.api,
      profile: MyProfile.fromJson(fx.myProfile),
    ));
    await _settle(tester);
    await _shot(tester, 'preview');
  });

  testWidgets('test panel', skip: skip, (tester) async {
    await _pump(tester, TestPanelScreen(session: _session()));
    final position = _mainScrollPosition(tester);
    position.jumpTo(position.maxScrollExtent * 0.55);
    await _settle(tester);
    await _shot(tester, 'test_panel');
  });

  testWidgets('likes', skip: skip, (tester) async {
    await _feed(tester);
    await _tapTab(tester, 'Симпатии');
    await _shot(tester, 'likes');
  });

  testWidgets('chats and chat', skip: skip, (tester) async {
    await _feed(tester);
    await _tapTab(tester, 'Чаты');
    await _shot(tester, 'chats');
    await tester.tap(find.text('Анна'));
    await _settle(tester);
    await _shot(tester, 'chat');
    await tester.tap(find.text('Открыть'));
    await _settle(tester);
    await _shot(tester, 'date_plan');
  });

  testWidgets('purchase and identity', skip: skip, (tester) async {
    final session = _session();
    await _pump(tester, PurchaseScreen(api: session.api, kind: PurchaseKind.premium));
    await _shot(tester, 'purchase_premium');
    await _pump(tester, PurchaseScreen(api: session.api, kind: PurchaseKind.superlikes));
    await _shot(tester, 'purchase_superlikes');
    await _pump(
        tester,
        ProfileSectionScreen(
          api: session.api,
          step: profileSteps.firstWhere((s) => s.id == 'identity'),
          profile: MyProfile.fromJson(fx.myProfile),
        ));
    await _shot(tester, 'section_identity');
  });

  testWidgets('date plan editor', skip: skip, (tester) async {
    final session = _session();
    await _pump(
        tester,
        DatePlanEditorScreen(
            api: session.api, matchId: 'match1', plan: DatePlan.fromJson(fx.datePlan)));
    await _shot(tester, 'date_plan_editor');
  });

  testWidgets('gacha', skip: skip, (tester) async {
    await _feed(tester);
    await _tapTab(tester, 'Удача');
    await _shot(tester, 'gacha');
  });

  testWidgets('profile', skip: skip, (tester) async {
    await _feed(tester);
    await _tapTab(tester, 'Профиль');
    await _shot(tester, 'profile');
    final position = _mainScrollPosition(tester);
    position.jumpTo(position.maxScrollExtent);
    await _settle(tester);
    await _shot(tester, 'profile_scrolled');
  });

  testWidgets('profile section', skip: skip, (tester) async {
    final session = _session();
    await _pump(
        tester,
        ProfileSectionScreen(
          api: session.api,
          step: profileSteps.firstWhere((s) => s.id == 'interests'),
          profile: MyProfile.fromJson(fx.myProfile),
        ));
    await _shot(tester, 'section_interests');
  });

  testWidgets('lifestyle section and filters', skip: skip, (tester) async {
    final session = _session();
    await _pump(
        tester,
        ProfileSectionScreen(
          api: session.api,
          step: profileSteps.firstWhere((s) => s.id == 'lifestyle'),
          profile: MyProfile.fromJson(fx.myProfile),
        ));
    await _shot(tester, 'section_lifestyle');
    await _pump(
        tester,
        ProfileSectionScreen(
          api: session.api,
          step: profileSteps.firstWhere((s) => s.id == 'tags'),
          profile: MyProfile.fromJson(fx.myProfile),
        ));
    await _shot(tester, 'section_tags');
    await _pump(tester, DiscoveryFiltersScreen(session: session, initial: const DiscoveryFilters()));
    await tester.drag(find.byType(ListView), const Offset(0, -300));
    await _settle(tester);
    await _shot(tester, 'filters_lifestyle');
  });

  testWidgets('wizard', skip: skip, (tester) async {
    final session = _session();
    await _pump(
        tester,
        ProfileWizardScreen(
            api: session.api, profile: MyProfile.fromJson(fx.myProfile), mode: WizardMode.refill));
    await _shot(tester, 'wizard_name');
    await tester.tap(find.text('Оставить текущее'));
    await _settle(tester);
    await _shot(tester, 'wizard_seeking');
  });

  testWidgets('onboarding', skip: skip, (tester) async {
    final session = _session();
    await _pump(tester, OnboardingScreen(api: session.api, profile: MyProfile.fromJson(fx.myProfile)));
    await _shot(tester, 'onboarding');
  });

  testWidgets('support', skip: skip, (tester) async {
    final session = _session();
    await _pump(tester, SupportScreen(api: session.api));
    await _shot(tester, 'support');
    await _pump(tester, TicketScreen(api: session.api, ticketId: 't1'));
    await _shot(tester, 'ticket');
  });

  testWidgets('feed dark', skip: skip, (tester) async {
    await _feed(tester, dark: true);
    await _shot(tester, 'feed_dark');
  });
}

// ------------------------------------------------- Fake network for images

class _ImageHttpOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? context) => _FakeHttpClient();
}

/// Answers GET with photo bytes (what NetworkImage needs); anything else
/// (the realtime `/events` stream via IOClient) fails as offline.
class _FakeHttpClient implements HttpClient {
  @override
  Future<HttpClientRequest> getUrl(Uri url) async => _FakeRequest(url);

  @override
  Future<HttpClientRequest> openUrl(String method, Uri url) =>
      Future.error(const SocketException('screenshots: offline'));

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeRequest implements HttpClientRequest {
  _FakeRequest(this.url);

  final Uri url;

  @override
  Future<HttpClientResponse> close() async => _FakeResponse(_photoBytes(url));

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeResponse extends Stream<List<int>> implements HttpClientResponse {
  _FakeResponse(this.bytes);

  final List<int> bytes;

  @override
  int get statusCode => 200;

  @override
  int get contentLength => bytes.length;

  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;

  @override
  StreamSubscription<List<int>> listen(void Function(List<int>)? onData,
          {Function? onError, void Function()? onDone, bool? cancelOnError}) =>
      Stream.value(bytes).listen(onData,
          onError: onError, onDone: onDone, cancelOnError: cancelOnError);

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

final Map<String, List<int>> _photoCache = {};

List<int> _photoBytes(Uri url) {
  final segments = url.pathSegments;
  final key = segments.length >= 2
      ? '${segments[segments.length - 2]}/${segments.last}'
      : url.path;
  return _photoCache.putIfAbsent(key, () {
    final file = File('$_photosDir/$key');
    return file.existsSync()
        ? file.readAsBytesSync()
        : _gradientPng(key.hashCode);
  });
}

/// 120x160 RGB gradient PNG, colors derived from [seed].
List<int> _gradientPng(int seed) {
  const w = 120, h = 160;
  final a = [(seed >> 16) & 0xFF, (seed >> 8) & 0xFF, seed & 0xFF];
  final b = [255 - a[1], a[2], 255 - a[0]];
  final raw = <int>[];
  for (var y = 0; y < h; y++) {
    raw.add(0); // filter: none
    for (var x = 0; x < w; x++) {
      final t = (x + y) / (w + h);
      for (var c = 0; c < 3; c++) {
        raw.add((a[c] + (b[c] - a[c]) * t).round());
      }
    }
  }
  List<int> chunk(String type, List<int> data) {
    final body = [...type.codeUnits, ...data];
    return [..._u32(data.length), ...body, ..._u32(_crc32(body))];
  }

  return [
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
    ...chunk('IHDR', [..._u32(w), ..._u32(h), 8, 2, 0, 0, 0]),
    ...chunk('IDAT', zlib.encode(raw)),
    ...chunk('IEND', const []),
  ];
}

List<int> _u32(int v) =>
    [(v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF];

int _crc32(List<int> bytes) {
  var crc = 0xFFFFFFFF;
  for (final byte in bytes) {
    crc ^= byte;
    for (var k = 0; k < 8; k++) {
      crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1;
    }
  }
  return crc ^ 0xFFFFFFFF;
}
