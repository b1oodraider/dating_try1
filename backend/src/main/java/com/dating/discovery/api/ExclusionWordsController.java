package com.dating.discovery.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.discovery.application.ExclusionWordsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/exclusion-words")
public class ExclusionWordsController {

    private final ExclusionWordsService exclusionWordsService;

    public ExclusionWordsController(ExclusionWordsService exclusionWordsService) {
        this.exclusionWordsService = exclusionWordsService;
    }

    @GetMapping
    public ResponseEntity<ExclusionWordsDto> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(new ExclusionWordsDto(
                exclusionWordsService.wordsOf(user.accountId()), ExclusionWordsService.MAX_WORDS,
                exclusionWordsService.isActive(user.accountId())));
    }

    @PutMapping
    public ResponseEntity<ExclusionWordsDto> replace(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @Valid @RequestBody ExclusionWordsUpdateDto dto) {
        return ResponseEntity.ok(new ExclusionWordsDto(
                exclusionWordsService.replaceWords(user.accountId(), dto.words()), ExclusionWordsService.MAX_WORDS, true));
    }
}
