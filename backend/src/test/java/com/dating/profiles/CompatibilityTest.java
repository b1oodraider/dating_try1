package com.dating.profiles;

import com.dating.profiles.application.Compatibility;
import com.dating.profiles.application.Compatibility.Card;
import com.dating.profiles.application.Compatibility.Scale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompatibilityTest {

    private static final Map<String, Scale> SCALES = Map.of("pace", new Scale(1, 3));

    private static Card pace(int position) {
        return new Card("PACE_" + position, "pace", position);
    }

    private static Card format(String code) {
        return new Card(code, "format", null);
    }

    @Test
    void neighboursOnAScaleAreCloserThanOpposites() {
        List<Card> slow = List.of(pace(1), format("WALK"));
        assertEquals(100, Compatibility.percent(slow, List.of(pace(1), format("WALK")), SCALES));
        assertEquals(75, Compatibility.percent(slow, List.of(pace(2), format("WALK")), SCALES)); // (0.5 + 1) / 2
        assertEquals(50, Compatibility.percent(slow, List.of(pace(3), format("WALK")), SCALES)); // (0 + 1) / 2
    }

    @Test
    void setCategoriesCountWhatIsShared() {
        List<Card> mine = List.of(pace(2), format("WALK"), format("COFFEE"));
        assertEquals(100, Compatibility.percent(mine, List.of(pace(2), format("COFFEE")), SCALES));
        assertEquals(50, Compatibility.percent(mine, List.of(pace(2), format("CINEMA")), SCALES));
    }

    @Test
    void severalCardsOnOneScaleUseTheirMeanAndUnplacedCardsFallBackToSets() {
        // mean position 2 vs 3 on a 1..3 scale: 1 − 1/2 = 0.5; with the matching format card: (0.5 + 1) / 2
        assertEquals(75, Compatibility.percent(List.of(pace(1), pace(3), format("WALK")),
                List.of(pace(3), format("WALK")), SCALES));
        // a card without a position (added by an editor later) compares the category as a set
        Card unplaced = new Card("PACE_NEW", "pace", null);
        assertEquals(50, Compatibility.percent(List.of(unplaced, format("WALK")), List.of(pace(3), format("WALK")), SCALES));
    }

    @Test
    void tooLittleInCommonGivesNoPercent() {
        assertNull(Compatibility.percent(List.of(pace(1)), List.of(pace(1)), SCALES));
        assertNull(Compatibility.percent(List.of(pace(1), format("WALK")), List.of(pace(1)), SCALES));
    }
}
