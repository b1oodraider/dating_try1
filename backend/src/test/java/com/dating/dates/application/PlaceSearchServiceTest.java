package com.dating.dates.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceSearchServiceTest {

    @Test
    void photonFeaturesBecomePlacesNamedByStreetWhenUnnamed() {
        String body = """
                {"features": [
                  {"geometry": {"coordinates": [37.62, 55.75]},
                   "properties": {"name": "Кофемания", "street": "Никольская улица", "housenumber": "10", "city": "Москва"}},
                  {"geometry": {"coordinates": [37.60, 55.76]},
                   "properties": {"street": "Тверская улица", "housenumber": "7", "district": "Тверской", "city": "Москва"}},
                  {"geometry": {"coordinates": [37.62, 55.75]},
                   "properties": {"name": "Кофемания", "street": "Никольская улица", "housenumber": "10", "city": "Москва"}},
                  {"geometry": {"coordinates": []}, "properties": {"name": "без точки"}},
                  {"geometry": {"coordinates": [1, 2]}, "properties": {}}
                ]}
                """;
        List<PlaceSearchService.Place> places = PlaceSearchService.parse(JsonMapper.builder().build().readTree(body));
        assertEquals(List.of(
                new PlaceSearchService.Place("Кофемания", "Никольская улица 10, Москва", 55.75, 37.62),
                new PlaceSearchService.Place("Тверская улица 7", "Тверской, Москва", 55.76, 37.60)), places);
    }
}
