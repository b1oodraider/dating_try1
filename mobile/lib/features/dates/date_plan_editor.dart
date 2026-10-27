import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/date_plan.dart';
import '../../core/theme/app_theme.dart';
import '../premium/purchase_screen.dart';
import 'map_picker_screen.dart';

const int _maxPoints = 5;

class _Draft {
  _Draft({this.place, this.at, this.activity = 'COFFEE', String comment = ''})
      : comment = TextEditingController(text: comment);

  PlacePick? place;
  DateTime? at;
  String activity;
  final TextEditingController comment;

  factory _Draft.of(DatePlanItem item) => _Draft(
        place: PlacePick(
          name: item.placeName,
          address: item.address,
          latitude: item.latitude,
          longitude: item.longitude,
          venue: item.venue,
        ),
        at: item.startsAt.toLocal(),
        activity: item.activity,
        comment: item.comment ?? '',
      );
}

/// Propose a date (or edit the plan in play — a counter proposal). Pops with the saved plan.
class DatePlanEditorScreen extends StatefulWidget {
  const DatePlanEditorScreen({super.key, required this.api, required this.matchId, this.plan});

  final DatingApiClient api;
  final String matchId;

  /// The plan to edit; null = a new proposal.
  final DatePlan? plan;

  @override
  State<DatePlanEditorScreen> createState() => _DatePlanEditorScreenState();
}

class _DatePlanEditorScreenState extends State<DatePlanEditorScreen> {
  late final List<_Draft> _drafts =
      widget.plan == null ? [_Draft()] : [for (final item in widget.plan!.items) _Draft.of(item)];
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final d in _drafts) {
      d.comment.dispose();
    }
    super.dispose();
  }

  Future<void> _pickPlace(_Draft draft) async {
    final pick = await Navigator.of(context).push<PlacePick>(
      MaterialPageRoute(builder: (_) => MapPickerScreen(api: widget.api, initial: draft.place)),
    );
    if (pick == null || !mounted) return;
    setState(() {
      draft.place = pick;
      final category = pick.venue?.category;
      if (category != null) draft.activity = category;
    });
  }

  Future<void> _pickTime(_Draft draft) async {
    final now = DateTime.now();
    final start = draft.at ?? DateTime(now.year, now.month, now.day + 1, 19);
    final date = await showDatePicker(
      context: context,
      initialDate: start,
      firstDate: now,
      lastDate: now.add(const Duration(days: 120)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(start));
    if (time == null || !mounted) return;
    setState(() => draft.at = DateTime(date.year, date.month, date.day, time.hour, time.minute));
  }

  Future<void> _save() async {
    final items = <DatePlanItem>[];
    for (final d in _drafts) {
      final place = d.place;
      final at = d.at;
      if (place == null || at == null) {
        setState(() => _error = 'У каждого пункта должны быть место и время');
        return;
      }
      items.add(DatePlanItem(
        placeName: place.name,
        address: place.address,
        latitude: place.latitude,
        longitude: place.longitude,
        startsAt: at,
        activity: d.activity,
        comment: d.comment.text.trim(),
        venue: place.venue,
      ));
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final plan = widget.plan;
      final saved = plan == null
          ? await widget.api.proposeDatePlan(widget.matchId, items)
          : await widget.api.editDatePlan(plan.id, plan.version, items);
      if (mounted) Navigator.of(context).pop(saved);
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isPremiumRequired) {
        setState(() => _saving = false);
        final bought = await openPurchase(context, api: widget.api, kind: PurchaseKind.premium);
        if (bought && mounted) _save();
        return;
      }
      setState(() {
        _saving = false;
        _error = e is ApiException && e.code == 'PLAN_CHANGED'
                ? 'План уже изменили — закройте и откройте его снова'
                : describeError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(widget.plan == null ? 'Предложить встречу' : 'Изменить план')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
        children: [
          for (final (index, d) in _drafts.indexed) ...[
            Row(
              children: [
                Expanded(child: Text('Пункт ${index + 1}', style: theme.textTheme.titleMedium)),
                if (_drafts.length > 1)
                  IconButton(
                    tooltip: 'Убрать пункт',
                    onPressed: () => setState(() => _drafts.removeAt(index).comment.dispose()),
                    icon: const Icon(Icons.close_rounded),
                  ),
              ],
            ),
            Card(
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  ListTile(
                    leading: const Icon(Icons.place_outlined),
                    title: Text(d.place?.name ?? 'Выбрать место на карте'),
                    subtitle: d.place?.venue?.ad != null
                        ? Text(d.place!.venue!.ad!.text)
                        : (d.place?.address == null ? null : Text(d.place!.address!)),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _pickPlace(d),
                  ),
                  ListTile(
                    leading: const Icon(Icons.schedule_rounded),
                    title: Text(d.at == null ? 'Выбрать дату и время' : formatDateTime(d.at!)),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _pickTime(d),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.sm),
                    child: Wrap(
                      spacing: Gaps.sm,
                      runSpacing: Gaps.sm,
                      children: [
                        for (final e in dateActivities.entries)
                          ChoiceChip(
                            chipAnimationStyle: instantChip,
                            label: Text(e.value),
                            selected: d.activity == e.key,
                            onSelected: (_) => setState(() => d.activity = e.key),
                          ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.md),
                    child: TextField(
                      controller: d.comment,
                      maxLength: 300,
                      maxLines: 3,
                      minLines: 1,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: const InputDecoration(labelText: 'Комментарий', hintText: 'Например: встречу у входа'),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: Gaps.sm),
          ],
          if (_drafts.length < _maxPoints)
            TextButton.icon(
              onPressed: () => setState(() => _drafts.add(_Draft())),
              icon: const Icon(Icons.add_rounded),
              label: const Text('Добавить пункт'),
            ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.md),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: Gaps.sm),
                  child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                ),
              FilledButton(
                onPressed: _saving ? null : _save,
                child: _saving
                    ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(widget.plan == null ? 'Предложить' : 'Отправить изменения'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
