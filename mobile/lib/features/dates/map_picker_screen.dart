import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/date_plan.dart';
import '../../core/theme/app_theme.dart';

/// What was picked on the map: a curated place, or any point with a name typed by hand.
class PlacePick {
  const PlacePick({required this.name, required this.latitude, required this.longitude, this.address, this.venue});

  final String name;
  final String? address;
  final double latitude;
  final double longitude;
  final Venue? venue;
}

/// Moscow centre: where the map opens when the location is unknown.
const LatLng _fallbackCenter = LatLng(55.7558, 37.6173);

/// The map tiles: 2GIS raster tiles, the same in the picker and the plan sheet.
/// shortcut: public tile servers without a key or a contract — fine for testing; before release sign a 2GIS
/// agreement (API key, limits) and put its tile address here.
TileLayer mapTiles() => TileLayer(
      urlTemplate: 'https://tile{s}.maps.2gis.com/tiles?x={x}&y={y}&z={z}',
      subdomains: const ['0', '1', '2', '3'],
      maxNativeZoom: 18,
      userAgentPackageName: 'com.dating.dating_mobile',
    );

/// Picks a place for a date point: curated places around (sponsored ones with their ad label) or a tap anywhere.
class MapPickerScreen extends StatefulWidget {
  const MapPickerScreen({super.key, required this.api, this.initial});

  final DatingApiClient api;
  final PlacePick? initial;

  @override
  State<MapPickerScreen> createState() => _MapPickerScreenState();
}

class _MapPickerScreenState extends State<MapPickerScreen> {
  final MapController _map = MapController();
  final TextEditingController _name = TextEditingController();
  LatLng _center = _fallbackCenter;
  List<Venue> _venues = const <Venue>[];
  Venue? _venue;
  LatLng? _point;
  String? _error;

  @override
  void initState() {
    super.initState();
    final initial = widget.initial;
    if (initial != null) {
      _point = LatLng(initial.latitude, initial.longitude);
      _venue = initial.venue;
      _name.text = initial.venue == null ? initial.name : '';
      _center = _point!;
      _loadVenues(_center);
    } else {
      _locate();
    }
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  /// My position if the permission is already there (asked by the profile's place step); else Moscow.
  Future<void> _locate() async {
    try {
      final permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.always || permission == LocationPermission.whileInUse) {
        final last = await Geolocator.getLastKnownPosition();
        if (last != null && mounted) {
          setState(() => _center = LatLng(last.latitude, last.longitude));
          _map.move(_center, 14);
        }
      }
    } catch (e) {
      debugPrint('No location: $e');
    }
    _loadVenues(_center);
  }

  Future<void> _loadVenues(LatLng around) async {
    try {
      final venues = await widget.api.getVenues(around.latitude, around.longitude);
      if (mounted) setState(() => _venues = venues);
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    }
  }

  void _pickVenue(Venue venue) => setState(() {
        _venue = venue;
        _point = LatLng(venue.latitude, venue.longitude);
      });

  void _pickPoint(LatLng point) => setState(() {
        _venue = null;
        _point = point;
      });

  void _done() {
    final point = _point;
    if (point == null) return;
    final venue = _venue;
    final name = venue?.name ?? _name.text.trim();
    if (name.isEmpty) {
      setState(() => _error = 'Назовите место');
      return;
    }
    Navigator.of(context).pop(PlacePick(
      name: name,
      address: venue?.address,
      latitude: point.latitude,
      longitude: point.longitude,
      venue: venue,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final venue = _venue;
    final point = _point;
    return Scaffold(
      appBar: AppBar(title: const Text('Место встречи')),
      body: Column(
        children: [
          Expanded(
            child: FlutterMap(
              mapController: _map,
              options: MapOptions(
                initialCenter: _center,
                initialZoom: 14,
                onTap: (_, latLng) => _pickPoint(latLng),
                onMapEvent: (event) {
                  if (event is MapEventMoveEnd) _loadVenues(event.camera.center);
                },
              ),
              children: [
                mapTiles(),
                MarkerLayer(markers: [
                  for (final v in _venues)
                    Marker(
                      point: LatLng(v.latitude, v.longitude),
                      width: 40,
                      height: 40,
                      child: GestureDetector(
                        onTap: () => _pickVenue(v),
                        child: Icon(
                          Icons.place_rounded,
                          size: 40,
                          color: v.id == venue?.id ? Brand.rose : (v.ad != null ? Brand.gold : Brand.ink),
                          semanticLabel: v.name,
                        ),
                      ),
                    ),
                  if (point != null && venue == null)
                    Marker(
                      point: point,
                      width: 40,
                      height: 40,
                      child: const Icon(Icons.place_rounded, size: 40, color: Brand.rose),
                    ),
                ]),
                const RichAttributionWidget(attributions: [TextSourceAttribution('© 2ГИС')]),
              ],
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(Gaps.md),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (venue != null) ...[
                    Text(venue.name, style: theme.textTheme.titleMedium),
                    if (venue.address != null)
                      Text(venue.address!, style: theme.textTheme.bodyMedium),
                    if (venue.description != null)
                      Text(venue.description!,
                          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    if (venue.ad != null) AdLabel(venue.ad!),
                  ] else if (point != null)
                    TextField(
                      controller: _name,
                      textCapitalization: TextCapitalization.sentences,
                      maxLength: 120,
                      decoration: const InputDecoration(labelText: 'Как назвать место', counterText: ''),
                    )
                  else
                    Text('Выберите место на карте или нажмите в любую точку',
                        style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: Gaps.xs),
                      child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                    ),
                  const SizedBox(height: Gaps.sm),
                  FilledButton(onPressed: point == null ? null : _done, child: const Text('Выбрать')),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// «Реклама · рекламодатель · erid …» under a sponsored place (38-ФЗ).
class AdLabel extends StatelessWidget {
  const AdLabel(this.ad, {super.key});

  final VenueAd ad;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: Gaps.xs),
        child: Text(ad.text,
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      );
}
