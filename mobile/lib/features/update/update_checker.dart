import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:ota_update/ota_update.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

/// A newer APK published by CI next to the web app.
class UpdateInfo {
  const UpdateInfo({required this.build, required this.apkUrl, this.sha256});

  final int build;

  /// Absolute download URL.
  final String apkUrl;

  /// Checked after download when present (CI writes it).
  final String? sha256;
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
  final sha = json['sha256'];
  return UpdateInfo(
      build: build,
      apkUrl: api.resolveMediaUrl(apk),
      sha256: sha is String && sha.length == 64 ? sha : null);
}

/// «Доступно обновление» with «Обновить»: downloads the APK inside the app and opens the system installer.
Future<void> showUpdateDialog(BuildContext context, UpdateInfo info) =>
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        icon: const Icon(Icons.system_update_rounded),
        title: const Text('Доступно обновление'),
        content: Text('Вышла новая версия приложения (сборка ${info.build}). '
            'Скачаем и предложим установить — данные и вход сохранятся.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Позже'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(dialogContext).pop();
              showDialog<void>(
                context: context,
                barrierDismissible: false,
                builder: (_) => _InstallDialog(info: info),
              );
            },
            child: const Text('Обновить'),
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

/// Download progress; the system installer takes over when the file is ready. On failure the APK can still be
/// opened in the browser.
class _InstallDialog extends StatefulWidget {
  const _InstallDialog({required this.info});

  final UpdateInfo info;

  @override
  State<_InstallDialog> createState() => _InstallDialogState();
}

class _InstallDialogState extends State<_InstallDialog> {
  StreamSubscription<OtaEvent>? _events;
  double? _progress;
  String? _error;

  @override
  void initState() {
    super.initState();
    try {
      _events = OtaUpdate()
          .execute(widget.info.apkUrl,
              destinationFilename: 'dating-update.apk', sha256checksum: widget.info.sha256)
          .listen(_onEvent, onError: (Object e) => _fail('$e'));
    } catch (e) {
      _error = '$e';
    }
  }

  void _onEvent(OtaEvent event) {
    if (!mounted) return;
    switch (event.status) {
      case OtaStatus.DOWNLOADING:
        setState(() => _progress = (double.tryParse(event.value ?? '') ?? 0) / 100);
      case OtaStatus.INSTALLING:
      case OtaStatus.INSTALLATION_DONE:
      case OtaStatus.CANCELED:
        Navigator.of(context).pop();
      case OtaStatus.PERMISSION_NOT_GRANTED_ERROR:
        _fail('Разрешите установку из этого приложения в настройках Android и попробуйте снова');
      case OtaStatus.CHECKSUM_ERROR:
        _fail('Файл скачался с ошибкой, попробуйте ещё раз');
      default:
        _fail(event.value ?? event.status.name);
    }
  }

  void _fail(String message) {
    if (mounted) setState(() => _error = message);
  }

  @override
  void dispose() {
    _events?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final error = _error;
    final progress = _progress;
    return AlertDialog(
      title: Text(error == null ? 'Скачиваем обновление' : 'Не получилось обновить'),
      content: error == null
          ? Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                LinearProgressIndicator(value: progress),
                const SizedBox(height: 12),
                Text(progress == null ? 'Подключаемся…' : '${(progress * 100).round()}%'),
              ],
            )
          : Text(error),
      actions: [
        if (error != null)
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              launchUrl(Uri.parse(widget.info.apkUrl), mode: LaunchMode.externalApplication);
            },
            child: const Text('Скачать в браузере'),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(error == null ? 'Скрыть' : 'Закрыть'),
        ),
      ],
    );
  }
}
