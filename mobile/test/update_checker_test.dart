import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/features/update/update_checker.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:package_info_plus/package_info_plus.dart';

void main() {
  final api = DatingApiClient(baseUrl: 'https://dating.test/api/v1');

  setUp(() => PackageInfo.setMockInitialValues(
        appName: 'Dating',
        packageName: 'dating',
        version: '0.1.0',
        buildNumber: '5',
        buildSignature: '',
      ));

  MockClient serving(String body, {List<Uri>? seen}) =>
      MockClient((request) async {
        seen?.add(request.url);
        return http.Response(body, 200);
      });

  test('a newer build is offered with an absolute APK URL', () async {
    final seen = <Uri>[];
    final info = await checkForUpdate(api,
        client: serving('{"build": 6, "apk": "/dating.apk"}', seen: seen));
    expect(seen.single.toString(), 'https://dating.test/app-version.json');
    expect(info?.build, 6);
    expect(info?.apkUrl, 'https://dating.test/dating.apk');
  });

  test('the same or an older build is no update', () async {
    expect(
        await checkForUpdate(api,
            client: serving('{"build": 5, "apk": "/a.apk"}')),
        isNull);
    expect(
        await checkForUpdate(api,
            client: serving('{"build": 1, "apk": "/a.apk"}')),
        isNull);
  });

  test('a malformed file is an error', () async {
    expect(checkForUpdate(api, client: serving('{"version": "1"}')),
        throwsFormatException);
  });
}
