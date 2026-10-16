package com.dating.admin.api;

import com.dating.admin.application.AdminCatalogService;
import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.CatalogAdminService.AuthorFields;
import com.dating.economy.application.CatalogAdminService.NewItem;
import com.dating.economy.domain.Author;
import com.dating.economy.domain.CatalogEntry;
import com.dating.economy.domain.ItemKind;
import com.dating.economy.persistence.CatalogRepository.ItemChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog items with images (CONTENT_EDITOR, ADMIN) and item authors (read: editors, write: ADMIN).
 * Items are never deleted: set active=false to hide one (owned copies stay with users).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {

    public record CreateItemRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
                    message = "Код: латинские заглавные буквы, цифры и _ (2..50 символов)") String code,
            @NotNull(message = "Укажите тип: CONSUMABLE или COSMETIC") ItemKind kind,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,29}$",
                    message = "Категория: латинские заглавные буквы, цифры и _ (2..30 символов)") String category,
            String equipSlot,
            @NotBlank(message = "Укажите название") @Size(max = 80) String title,
            String rarity,
            Map<String, Object> attributes,
            Boolean active,
            Instant availableFrom,
            Instant availableUntil,
            UUID authorId) {
    }

    /**
     * Full replace of the editable fields (code, kind, category and slot are fixed).
     */
    public record UpdateItemRequest(
            @NotBlank(message = "Укажите название") @Size(max = 80) String title,
            String rarity,
            @NotNull(message = "Укажите атрибуты (объект)") Map<String, Object> attributes,
            @NotNull(message = "Укажите, активен ли предмет") Boolean active,
            Instant availableFrom,
            Instant availableUntil,
            UUID authorId) {
    }

    public record AuthorRequest(
            @NotBlank(message = "Укажите имя автора") @Size(max = 80) String displayName,
            UUID accountId,
            @Min(0) @Max(100) Integer revenueSharePercent,
            @Size(max = 500) String contactNote) {
    }

    private final AdminCatalogService service;

    public AdminCatalogController(AdminCatalogService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- items

    @GetMapping("/catalog/items")
    public List<CatalogEntry> items(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String category) {
        return service.items(user.accountId(), kind, category);
    }

    @GetMapping("/catalog/items/{code}")
    public CatalogEntry item(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        return service.item(user.accountId(), code);
    }

    @PostMapping("/catalog/items")
    public ResponseEntity<CatalogEntry> create(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody CreateItemRequest request) {
        ItemChanges fields = new ItemChanges(request.title().strip(), request.rarity(),
                request.attributes() == null ? Map.of() : request.attributes(),
                request.active() == null || request.active(),
                request.availableFrom(), request.availableUntil(), request.authorId());
        NewItem item = new NewItem(request.code(), request.kind(), request.category(), request.equipSlot(), fields);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.accountId(), item));
    }

    @PutMapping("/catalog/items/{code}")
    public CatalogEntry update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                               @Valid @RequestBody UpdateItemRequest request) {
        return service.update(user.accountId(), code, new ItemChanges(request.title().strip(), request.rarity(),
                request.attributes(), request.active(), request.availableFrom(), request.availableUntil(),
                request.authorId()));
    }

    /**
     * multipart field {@code file}: PNG, WebP or JPEG up to 5 MB; sets attributes.imageUrl.
     */
    @PostMapping(value = "/catalog/items/{code}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CatalogEntry uploadImage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                    @RequestParam("file") MultipartFile file) {
        return service.uploadImage(user.accountId(), code, file);
    }

    // ---------------------------------------------------------------- authors

    @GetMapping("/authors")
    public List<Author> authors(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.authors(user.accountId());
    }

    @PostMapping("/authors")
    public ResponseEntity<Author> createAuthor(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody AuthorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAuthor(user.accountId(), fields(request)));
    }

    @PutMapping("/authors/{id}")
    public Author updateAuthor(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                               @Valid @RequestBody AuthorRequest request) {
        return service.updateAuthor(user.accountId(), id, fields(request));
    }

    @DeleteMapping("/authors/{id}")
    public ResponseEntity<Void> deleteAuthor(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.deleteAuthor(user.accountId(), id);
        return ResponseEntity.noContent().build();
    }

    private static AuthorFields fields(AuthorRequest request) {
        String note = request.contactNote() == null || request.contactNote().isBlank() ? null : request.contactNote().strip();
        return new AuthorFields(request.displayName().strip(), request.accountId(), request.revenueSharePercent(), note);
    }
}
