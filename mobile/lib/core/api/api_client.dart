import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/profile.dart';
import '../models/communication_tag.dart';

class DatingApiClient {
  final String baseUrl;
  String? _authToken;

  DatingApiClient({this.baseUrl = 'http://localhost:8080/api/v1'});

  void setAuthToken(String? token) {
    _authToken = token;
  }

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (_authToken != null) 'Authorization': 'Bearer $_authToken',
      };

  // Auth: Request OTP
  Future<Map<String, dynamic>> requestOtp(String phone) async {
    final res = await http.post(
      Uri.parse('$baseUrl/auth/otp/request'),
      headers: _headers,
      body: jsonEncode({'phone': phone}),
    );
    if (res.statusCode >= 400) {
      throw Exception('Ошибка отправки кода');
    }
    return jsonDecode(res.body) as Map<String, dynamic>;
  }

  // Auth: Verify OTP
  Future<Map<String, dynamic>> verifyOtp(String challengeId, String code, String phone) async {
    final res = await http.post(
      Uri.parse('$baseUrl/auth/otp/verify?phone=${Uri.encodeComponent(phone)}'),
      headers: _headers,
      body: jsonEncode({'challengeId': challengeId, 'code': code}),
    );
    if (res.statusCode >= 400) {
      throw Exception('Неверный код подтверждения');
    }
    final data = jsonDecode(res.body) as Map<String, dynamic>;
    _authToken = data['token'] as String?;
    return data;
  }

  // Discovery: Search Profiles
  Future<List<Profile>> getDiscoveryProfiles({
    String? city,
    List<String>? tagCodes,
    String tagLogic = 'OR',
  }) async {
    final uri = Uri.parse('$baseUrl/discovery/profiles').replace(queryParameters: {
      if (city != null && city != 'ALL') 'city': city,
      if (tagCodes != null && tagCodes.isNotEmpty) 'tagCodes': tagCodes.join(','),
      'tagLogic': tagLogic,
    });

    final res = await http.get(uri, headers: _headers);
    if (res.statusCode >= 400) {
      throw Exception('Ошибка загрузки анкет');
    }
    final data = jsonDecode(res.body) as Map<String, dynamic>;
    final items = data['items'] as List<dynamic>? ?? [];
    return items.map((e) => Profile.fromJson(e as Map<String, dynamic>)).toList();
  }

  // Tags Catalog
  Future<List<CommunicationTag>> getTags() async {
    final res = await http.get(Uri.parse('$baseUrl/tags'), headers: _headers);
    if (res.statusCode >= 400) {
      throw Exception('Ошибка загрузки карточек');
    }
    final list = jsonDecode(res.body) as List<dynamic>;
    return list.map((e) => CommunicationTag.fromJson(e as Map<String, dynamic>)).toList();
  }
}
