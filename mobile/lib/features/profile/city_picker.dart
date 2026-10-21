import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/city.dart';

/// Bottom sheet: type a name (any spelling — «Питер», «Мск», with typos) and pick a city.
Future<CitySuggestion?> pickCity(BuildContext context, DatingApiClient api) {
  return showModalBottomSheet<CitySuggestion>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => _CityPicker(api: api),
  );
}

class _CityPicker extends StatefulWidget {
  const _CityPicker({required this.api});

  final DatingApiClient api;

  @override
  State<_CityPicker> createState() => _CityPickerState();
}

class _CityPickerState extends State<_CityPicker> {
  List<CitySuggestion> _results = const <CitySuggestion>[];
  Timer? _debounce;
  String? _error;
  int _request = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _onChanged(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () => _search(text));
  }

  Future<void> _search(String text) async {
    final request = ++_request;
    try {
      final results = await widget.api.searchCities(text);
      if (!mounted || request != _request) return;
      setState(() {
        _results = results;
        _error = null;
      });
    } catch (e) {
      if (mounted && request == _request) setState(() => _error = describeError(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.7,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: TextField(
                autofocus: true,
                decoration: const InputDecoration(
                  labelText: 'Город',
                  hintText: 'Например, Питер или Казань',
                  prefixIcon: Icon(Icons.search),
                ),
                onChanged: _onChanged,
              ),
            ),
            if (_error != null) Text(_error!),
            Expanded(
              child: ListView.builder(
                itemCount: _results.length,
                itemBuilder: (context, index) {
                  final city = _results[index];
                  return ListTile(
                    title: Text(city.name),
                    subtitle: Text([
                      if (city.region.isNotEmpty && city.region != city.name) city.region,
                      if (city.matchedName != null) 'найдено по «${city.matchedName}»',
                    ].join(' · ')),
                    onTap: () => Navigator.of(context).pop(city),
                  );
                },
              ),
            ),
            const Padding(
              padding: EdgeInsets.all(8),
              child: Text('Данные о городах: GeoNames',
                  style: TextStyle(fontSize: 11, color: Colors.grey)),
            ),
          ],
        ),
      ),
    );
  }
}
