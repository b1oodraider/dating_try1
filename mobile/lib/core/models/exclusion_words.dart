import 'json_utils.dart';

/// Private feed filter of `GET/PUT /me/exclusion-words`.
///
/// Profiles whose "about" text or active daily status contains any of these
/// words (in any grammatical form) are hidden from the caller's discovery
/// feed. The list is never shown to anyone else.
class ExclusionWords {
  const ExclusionWords({
    required this.words,
    this.maxWords = defaultMaxWords,
  });

  static const int defaultMaxWords = 20;
  static const int minWordLength = 2;
  static const int maxWordLength = 30;

  /// Normalized by the server: trimmed, lowercase, no duplicates, in order.
  final List<String> words;

  /// Upper bound for [words] reported by the server (20 by the contract).
  final int maxWords;

  factory ExclusionWords.fromJson(Map<String, dynamic> json) {
    final rawWords = json['words'];
    final words = rawWords is List
        ? rawWords
            .whereType<String>()
            .map((word) => word.trim())
            .where((word) => word.isNotEmpty)
            .toList(growable: false)
        : const <String>[];
    final maxWords = asIntOrNull(json['maxWords']);
    return ExclusionWords(
      words: List<String>.unmodifiable(words),
      maxWords: (maxWords == null || maxWords <= 0) ? defaultMaxWords : maxWords,
    );
  }
}
