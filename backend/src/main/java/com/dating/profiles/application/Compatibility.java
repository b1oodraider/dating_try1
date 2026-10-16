package com.dating.profiles.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compatibility by cards, 0..100. Only categories both people filled in count; each counts equally.
 * <ul>
 *   <li>Scale category (all cards involved have a position): 1 − |mean position A − mean position B| / scale span,
 *       so neighbours on the scale are close and opposite ends are far.</li>
 *   <li>Other categories: shared cards / the smaller selection (choosing different things there is not a clash,
 *       just nothing in common).</li>
 * </ul>
 * No percent (null) with fewer than {@value #MIN_SHARED_CATEGORIES} shared categories: too little to say.
 */
public final class Compatibility {

    public record Card(String code, String category, Integer scalePosition) {
    }

    /** Positions used on an active scale category. */
    public record Scale(int min, int max) {
        int span() {
            return max - min;
        }
    }

    static final int MIN_SHARED_CATEGORIES = 2;

    private Compatibility() {
    }

    public static Integer percent(Collection<Card> mine, Collection<Card> theirs, Map<String, Scale> scales) {
        Map<String, List<Card>> a = mine.stream().collect(Collectors.groupingBy(Card::category));
        Map<String, List<Card>> b = theirs.stream().collect(Collectors.groupingBy(Card::category));
        Set<String> shared = new HashSet<>(a.keySet());
        shared.retainAll(b.keySet());
        if (shared.size() < MIN_SHARED_CATEGORIES) {
            return null;
        }
        double sum = 0;
        for (String category : shared) {
            sum += similarity(a.get(category), b.get(category), scales.get(category));
        }
        return (int) Math.round(100 * sum / shared.size());
    }

    static double similarity(List<Card> a, List<Card> b, Scale scale) {
        OptionalDouble posA = meanPosition(a);
        OptionalDouble posB = meanPosition(b);
        if (scale != null && posA.isPresent() && posB.isPresent()) {
            return scale.span() == 0 ? 1 : 1 - Math.abs(posA.getAsDouble() - posB.getAsDouble()) / scale.span();
        }
        Set<String> codesA = a.stream().map(Card::code).collect(Collectors.toSet());
        long common = b.stream().map(Card::code).filter(codesA::contains).distinct().count();
        return (double) common / Math.min(codesA.size(), b.stream().map(Card::code).distinct().count());
    }

    /** Empty unless every card has a position. */
    private static OptionalDouble meanPosition(List<Card> cards) {
        if (cards.stream().anyMatch(c -> c.scalePosition() == null)) {
            return OptionalDouble.empty();
        }
        return cards.stream().mapToInt(Card::scalePosition).average();
    }
}
