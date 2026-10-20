package com.dating.profiles.api;

import com.dating.profiles.persistence.CityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** City suggestions for the profile and the feed filter (public, like /tags). */
@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    private final CityRepository cities;

    public CityController(CityRepository cities) {
        this.cities = cities;
    }

    /** @param matchedName the name that matched when it is not the official one, e.g. «Питер» for Санкт-Петербург */
    public record CityDto(int id, String name, String region, String matchedName) {
    }

    @GetMapping
    public List<CityDto> search(@RequestParam(defaultValue = "") String query,
                                @RequestParam(defaultValue = "10") int limit) {
        return cities.search(query, Math.max(1, Math.min(limit, 20))).stream()
                .map(c -> new CityDto(c.id(), c.name(), c.region(), c.matchedName()))
                .toList();
    }
}
