import '../../core/models/exclusion_words.dart';

// Client-side mirror of the server rules for exclusion words
// (docs/api-contract.md, "Exclusion words"). The server stays the source of
// truth; this only gives instant feedback.

final RegExp _singleWordPattern = RegExp(r'^[\p{L}\p{N}]+$', unicode: true);

/// Same normalization as the server: trim + lowercase.
String normalizeExclusionWord(String raw) => raw.trim().toLowerCase();

/// Returns a Russian error message, or null when [word] (already normalized
/// with [normalizeExclusionWord]) can be added to [existing].
String? validateExclusionWord(
  String word, {
  required List<String> existing,
  required int maxWords,
}) {
  if (word.isEmpty) return 'Введите слово';
  if (existing.any((other) => other.toLowerCase() == word)) {
    return 'Это слово уже есть в списке';
  }
  if (existing.length >= maxWords) {
    return 'Можно добавить не больше $maxWords слов';
  }
  if (word.length < ExclusionWords.minWordLength) {
    return 'Слово должно быть не короче ${ExclusionWords.minWordLength} символов';
  }
  if (word.length > ExclusionWords.maxWordLength) {
    return 'Слово должно быть не длиннее ${ExclusionWords.maxWordLength} символов';
  }
  if (!_singleWordPattern.hasMatch(word)) {
    return 'Только одно слово: буквы и цифры, без пробелов и знаков препинания';
  }
  return null;
}
