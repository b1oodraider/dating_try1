package com.dating.support.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.support.application.FaqService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** FAQ: GET /faq is public; /admin/faq needs CONTENT_EDITOR, SUPPORT or ADMIN (else 403 ROLE_REQUIRED). */
@RestController
@RequestMapping("/api/v1")
public class FaqController {

    public record FaqRequest(
            @NotBlank(message = "Напишите вопрос") @Size(max = 200, message = "Вопрос не длиннее 200 символов")
            String question,
            @NotBlank(message = "Напишите ответ") @Size(max = 4000, message = "Ответ не длиннее 4000 символов")
            String answer,
            @Min(0) @Max(100000) Integer sortOrder,
            Boolean active
    ) {
        FaqService.Change change() {
            return new FaqService.Change(question.trim(), answer.trim(), sortOrder == null ? 0 : sortOrder,
                    active == null || active);
        }
    }

    private final FaqService service;

    public FaqController(FaqService service) {
        this.service = service;
    }

    @GetMapping("/faq")
    public List<FaqService.FaqEntry> faq() {
        return service.published();
    }

    @GetMapping("/admin/faq")
    public List<FaqService.FaqItem> all(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.all(user.accountId());
    }

    @PostMapping("/admin/faq")
    public ResponseEntity<FaqService.FaqItem> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @Valid @RequestBody FaqRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.accountId(), request.change()));
    }

    @PutMapping("/admin/faq/{id}")
    public FaqService.FaqItem update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                     @Valid @RequestBody FaqRequest request) {
        return service.update(user.accountId(), id, request.change());
    }

    @DeleteMapping("/admin/faq/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.delete(user.accountId(), id);
        return ResponseEntity.noContent().build();
    }
}
