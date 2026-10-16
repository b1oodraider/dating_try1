/// Small display formatters (no intl dependency).
library;

String _two(int value) => value.toString().padLeft(2, '0');

/// `dd.MM.yyyy HH:mm` in local time.
String formatDateTime(DateTime value) {
  final local = value.toLocal();
  return '${_two(local.day)}.${_two(local.month)}.${local.year} '
      '${_two(local.hour)}:${_two(local.minute)}';
}

/// `7` for whole numbers, `0.5` otherwise (at most 2 decimals).
String formatDays(double days) {
  if (days == days.roundToDouble()) return days.toStringAsFixed(0);
  var text = days.toStringAsFixed(2);
  while (text.endsWith('0')) {
    text = text.substring(0, text.length - 1);
  }
  return text;
}
