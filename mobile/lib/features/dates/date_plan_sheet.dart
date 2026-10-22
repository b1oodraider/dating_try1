import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/date_plan.dart';
import '../../core/theme/app_theme.dart';
import '../premium/purchase_screen.dart';
import 'date_plan_editor.dart';
import 'map_picker_screen.dart';

/// The date plan with its actions: accept / decline (the side it was proposed to), edit (premium), cancel.
/// Loads [planId], or the plan in play of [matchId].
Future<void> showDatePlan(BuildContext context,
        {required DatingApiClient api, required String matchId, String? planId}) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _DatePlanSheet(api: api, matchId: matchId, planId: planId),
    );

class _DatePlanSheet extends StatefulWidget {
  const _DatePlanSheet({required this.api, required this.matchId, this.planId});

  final DatingApiClient api;
  final String matchId;
  final String? planId;

  @override
  State<_DatePlanSheet> createState() => _DatePlanSheetState();
}

class _DatePlanSheetState extends State<_DatePlanSheet> {
  DatePlan? _plan;
  bool _loading = true;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final id = widget.planId;
      final plan = id == null ? await widget.api.getDatePlan(widget.matchId) : await widget.api.getDatePlanById(id);
      if (mounted) {
        setState(() {
          _plan = plan;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = describeError(e);
        });
      }
    }
  }

  Future<void> _act(String action) async {
    final plan = _plan;
    if (plan == null) return;
    setState(() => _busy = true);
    try {
      final updated = await widget.api.answerDatePlan(plan.id, action);
      if (mounted) setState(() => _plan = updated);
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Premium page on top of the sheet; after a purchase the plan reloads (now editable).
  Future<void> _buyPremium() async {
    final bought = await openPurchase(context, api: widget.api, kind: PurchaseKind.premium);
    if (bought && mounted) _load();
  }

  Future<void> _edit() async {
    final plan = _plan;
    final saved = await Navigator.of(context).push<DatePlan>(MaterialPageRoute(
      builder: (_) => DatePlanEditorScreen(api: widget.api, matchId: widget.matchId, plan: plan),
    ));
    if (saved != null && mounted) setState(() => _plan = saved);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final plan = _plan;
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.8,
      maxChildSize: 0.95,
      builder: (_, controller) => ListView(
        controller: controller,
        padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.lg),
        children: [
          Text('План встречи', style: theme.textTheme.headlineSmall),
          const SizedBox(height: Gaps.sm),
          if (_loading)
            const Padding(padding: EdgeInsets.all(Gaps.xl), child: Center(child: CircularProgressIndicator()))
          else if (plan == null)
            Text(_error ?? 'Плана пока нет', style: theme.textTheme.bodyLarge)
          else ...[
            Text(plan.statusText,
                style: theme.textTheme.titleMedium?.copyWith(
                    color: plan.status == 'ACCEPTED' ? const Color(0xFF15803D) : theme.colorScheme.onSurfaceVariant)),
            const SizedBox(height: Gaps.md),
            ClipRRect(
              borderRadius: BorderRadius.circular(Radii.md),
              child: SizedBox(
                height: 180,
                child: FlutterMap(
                  options: MapOptions(
                    initialCameraFit: plan.items.length > 1
                        ? CameraFit.coordinates(
                            coordinates: [for (final i in plan.items) LatLng(i.latitude, i.longitude)],
                            padding: const EdgeInsets.all(32))
                        : null,
                    initialCenter: LatLng(plan.items.first.latitude, plan.items.first.longitude),
                    initialZoom: 14,
                    interactionOptions: const InteractionOptions(flags: InteractiveFlag.none),
                  ),
                  children: [
                    TileLayer(
                        urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                        userAgentPackageName: 'com.dating.dating_mobile'),
                    MarkerLayer(markers: [
                      for (final i in plan.items)
                        Marker(
                          point: LatLng(i.latitude, i.longitude),
                          width: 36,
                          height: 36,
                          child: const Icon(Icons.place_rounded, size: 36, color: Brand.rose),
                        ),
                    ]),
                  ],
                ),
              ),
            ),
            const SizedBox(height: Gaps.md),
            for (final item in plan.items)
              Padding(
                padding: const EdgeInsets.only(bottom: Gaps.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(formatDateTime(item.startsAt), style: theme.textTheme.titleSmall),
                    Text('${item.placeName} · ${dateActivities[item.activity] ?? item.activity}',
                        style: theme.textTheme.bodyLarge),
                    if (item.address != null)
                      Text(item.address!,
                          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    if (item.venue?.ad != null) AdLabel(item.venue!.ad!),
                    if (item.comment != null) Text('«${item.comment}»', style: theme.textTheme.bodyMedium),
                  ],
                ),
              ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: Gaps.sm),
                child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            if (plan.canRespond) ...[
              FilledButton(onPressed: _busy ? null : () => _act('accept'), child: const Text('Принять')),
              const SizedBox(height: Gaps.sm),
              OutlinedButton(onPressed: _busy ? null : () => _act('decline'), child: const Text('Отклонить')),
            ],
            if (plan.open) ...[
              const SizedBox(height: Gaps.sm),
              OutlinedButton.icon(
                onPressed: _busy ? null : (plan.canEdit ? _edit : _buyPremium),
                icon: Icon(plan.canEdit ? Icons.edit_outlined : Icons.lock_outline_rounded),
                label: const Text('Изменить (встречное предложение)'),
              ),
              if (!plan.canEdit)
                Text('Изменять план можно с премиумом',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              TextButton(onPressed: _busy ? null : () => _act('cancel'), child: const Text('Отменить план')),
            ],
          ],
        ],
      ),
    );
  }
}
