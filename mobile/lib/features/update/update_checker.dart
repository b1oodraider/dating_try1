import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

/// A newer APK published by CI next to the web app.
class UpdateInfo {
  const UpdateInfo({required this.build, required this.apkUrl});

  final int build;

  /// Absolute download URL.
  final String apkUrl;
}

/// Reads `<api origin>/app-version.json` (`{"build": 42, "apk": "/dating.apk"}`)
/// and returns it when its build is newer than the installed one; null when
/// up to date or not on Android (the APK is Android only). Throws on network
/// or format errors.
Future<UpdateInfo?> checkForUpdate(DatingApiClient api,
    {http.Client? client}) async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return null;
  final url = Uri.parse(api.resolveMediaUrl('/app-version.json'));
  final response = await (client?.get ?? http.get)(url).timeout(api.timeout);
  if (response.statusCode != 200) {
    throw FormatException('app-version.json: HTTP ${response.statusCode}');
  }
  final json = jsonDecode(utf8.decode(response.bodyBytes));
  final build = json is Map ? json['build'] : null;
  final apk = json is Map ? json['apk'] : null;
  if (build is! int || apk is! String || apk.isEmpty) {
    throw const FormatException('app-version.json: нет build или apk');
  }
  final installed =
      int.tryParse((await PackageInfo.fromPlatform()).buildNumber) ?? 0;
  if (build <= installed) return null;
  return UpdateInfo(build: build, apkUrl: api.resolveMediaUrl(apk));
}

/// «Доступно обновление» with «Скачать», which opens the APK in the browser.
Future<void> showUpdateDialog(BuildContext context, UpdateInfo info) =>
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        icon: const Icon(Icons.system_update_rounded),
        title: const Text('Доступно обновление'),
        content: Text('Вышла новая версия приложения (сборка ${info.build}). '
            'Скачайте APK и установите его поверх текущего.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Позже'),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.of(dialogContext).pop();
              final opened = await launchUrl(Uri.parse(info.apkUrl),
                  mode: LaunchMode.externalApplication);
              if (!opened && context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                    content: Text('Не удалось открыть ссылку на APK')));
              }
            },
            child: const Text('Скачать'),
          ),
        ],
      ),
    );

/// Settings row «Проверить обновления».
class CheckForUpdatesTile extends StatefulWidget {
  const CheckForUpdatesTile({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<CheckForUpdatesTile> createState() => _CheckForUpdatesTileState();
}

class _CheckForUpdatesTileState extends State<CheckForUpdatesTile> {
  bool _checking = false;

  Future<void> _check() async {
    setState(() => _checking = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final info = await checkForUpdate(widget.api);
      if (!mounted) return;
      if (info == null) {
        messenger.showSnackBar(
            const SnackBar(content: Text('У вас последняя версия')));
      } else {
        await showUpdateDialog(context, info);
      }
    } catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text('Не удалось проверить обновления: '
              '${e is FormatException ? e.message : describeError(e)}')));
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListTile(
        leading: const Icon(Icons.system_update_outlined),
        title: const Text('Проверить обновления'),
        trailing: _checking
            ? const SizedBox.square(
                dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
            : null,
        onTap: _checking ? null : _check,
      );
}
