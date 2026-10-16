package com.dating.economy.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.economy.domain.Author;
import com.dating.economy.domain.CatalogEntry;
import com.dating.economy.domain.CosmeticSlot;
import com.dating.economy.domain.ItemKind;
import com.dating.economy.persistence.CatalogRepository;
import com.dating.economy.persistence.CatalogRepository.ItemChanges;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog items and their authors, edited by staff without migrations.
 * <p>
 * Fixed after creation: code (wallets, ledger and clients refer to it), kind (stackable or owned),
 * category and equip slot (what users own and have equipped must keep meaning the same).
 * To "change" those, hide the item and create a new one. Items are never deleted, only deactivated.
 * <p>
 * Role checks and the audit log are done by the admin API around these calls.
 */
@Service
public class CatalogAdminService {

    private static final Set<String> RARITIES = Set.of("COMMON", "RARE", "EPIC", "LEGENDARY");

    public record NewItem(String code, ItemKind kind, String category, String equipSlot, ItemChanges fields) {
    }

    public record AuthorFields(String displayName, UUID accountId, Integer revenueSharePercent, String contactNote) {
    }

    private final CatalogRepository repository;
    private final AuthorsProperties authorsProperties;

    public CatalogAdminService(CatalogRepository repository, AuthorsProperties authorsProperties) {
        this.repository = repository;
        this.authorsProperties = authorsProperties;
    }

    // ---------------------------------------------------------------- items

    @Transactional(readOnly = true)
    public List<CatalogEntry> items(String kind, String category) {
        return repository.entries(false, kind, category);
    }

    @Transactional(readOnly = true)
    public CatalogEntry requireItem(String code) {
        return repository.entry(code).orElseThrow(() -> itemNotFound(code));
    }

    @Transactional
    public CatalogEntry create(NewItem item) {
        String slot = resolveSlot(item.kind(), item.category(), item.equipSlot());
        validate(item.kind(), item.category(), item.fields());
        if (!repository.insert(item.code(), item.kind(), item.category(), slot, item.fields())) {
            throw ApiException.conflict("ITEM_EXISTS", "Предмет с таким кодом уже есть: " + item.code());
        }
        return requireItem(item.code());
    }

    @Transactional
    public CatalogEntry update(String code, ItemChanges fields) {
        CatalogEntry current = repository.lockEntry(code).orElseThrow(() -> itemNotFound(code));
        validate(current.kind(), current.category(), fields);
        repository.update(code, fields);
        return requireItem(code);
    }

    /**
     * Sets attributes.imageUrl after an image upload; other attributes stay as they are.
     */
    @Transactional
    public CatalogEntry setImageUrl(String code, String imageUrl) {
        CatalogEntry current = repository.lockEntry(code).orElseThrow(() -> itemNotFound(code));
        Map<String, Object> attributes = new LinkedHashMap<>(current.attributes());
        attributes.put("imageUrl", imageUrl);
        repository.update(code, new ItemChanges(current.title(), current.rarity(), attributes, current.active(),
                current.availableFrom(), current.availableUntil(), current.authorId()));
        return requireItem(code);
    }

    private static String resolveSlot(ItemKind kind, String category, String equipSlot) {
        if (kind == ItemKind.CONSUMABLE) {
            if (equipSlot != null) {
                throw invalid("equipSlot", "Расходники не надеваются");
            }
            return null;
        }
        // Built-in slot categories are worn in the slot of the same name.
        boolean slotCategory = CosmeticSlot.parse(category).map(s -> s.name().equals(category)).orElse(false);
        String slot = equipSlot == null && slotCategory ? category : equipSlot;
        if (slot == null) {
            return null;
        }
        if (CosmeticSlot.parse(slot).filter(s -> s.name().equals(slot)).isEmpty()) {
            throw invalid("equipSlot", "Неизвестный слот: " + slot);
        }
        if (slotCategory && !slot.equals(category)) {
            throw invalid("equipSlot", "Предмет категории " + category + " надевается только в слот " + category);
        }
        if ("STICKER_PACK".equals(category)) {
            throw invalid("equipSlot", "Наборы стикеров не надеваются");
        }
        return slot;
    }

    private void validate(ItemKind kind, String category, ItemChanges fields) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (fields.rarity() != null && !RARITIES.contains(fields.rarity())) {
            errors.put("rarity", "Редкость: COMMON, RARE, EPIC или LEGENDARY");
        }
        Instant from = fields.availableFrom();
        Instant until = fields.availableUntil();
        if (from != null && until != null && !from.isBefore(until)) {
            errors.put("availableUntil", "Конец доступности должен быть позже начала");
        }
        if (fields.authorId() != null && repository.author(fields.authorId()).isEmpty()) {
            errors.put("authorId", "Автор не найден");
        }
        errors.putAll(CatalogAttributesValidator.validate(kind, category, fields.attributes()));
        if (!errors.isEmpty()) {
            boolean onlyAttributes = errors.keySet().stream().allMatch(k -> k.startsWith("attributes."));
            throw ApiException.validation(onlyAttributes ? "INVALID_ATTRIBUTES" : "VALIDATION_ERROR",
                    onlyAttributes ? "Атрибуты не подходят для категории " + category : "Ошибка валидации входных данных",
                    errors);
        }
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation("VALIDATION_ERROR", message, Map.of(field, message));
    }

    private static ApiException itemNotFound(String code) {
        return ApiException.notFound("ITEM_NOT_FOUND", "Предмет не найден: " + code);
    }

    // ---------------------------------------------------------------- authors

    @Transactional(readOnly = true)
    public List<Author> authors() {
        return repository.authors();
    }

    @Transactional(readOnly = true)
    public Author requireAuthor(UUID id) {
        return repository.author(id).orElseThrow(CatalogAdminService::authorNotFound);
    }

    @Transactional
    public Author createAuthor(AuthorFields fields) {
        UUID id = UUID.randomUUID();
        checkAccount(fields.accountId());
        int share = fields.revenueSharePercent() == null
                ? authorsProperties.defaultRevenueSharePercent() : fields.revenueSharePercent();
        repository.insertAuthor(id, fields.displayName(), fields.accountId(), share, fields.contactNote());
        return requireAuthor(id);
    }

    /**
     * Full replace; a missing revenueSharePercent keeps the current one.
     */
    @Transactional
    public Author updateAuthor(UUID id, AuthorFields fields) {
        if (!repository.lockAuthor(id)) {
            throw authorNotFound();
        }
        checkAccount(fields.accountId());
        Author current = requireAuthor(id);
        int share = fields.revenueSharePercent() == null ? current.revenueSharePercent() : fields.revenueSharePercent();
        repository.updateAuthor(id, fields.displayName(), fields.accountId(), share, fields.contactNote());
        return requireAuthor(id);
    }

    /**
     * Only authors without items can be deleted: sales history must keep pointing to someone.
     */
    @Transactional
    public void deleteAuthor(UUID id) {
        if (!repository.lockAuthor(id)) {
            throw authorNotFound();
        }
        if (requireAuthor(id).itemCount() > 0) {
            throw ApiException.conflict("AUTHOR_IN_USE", "У автора есть предметы в каталоге");
        }
        repository.deleteAuthor(id);
    }

    private void checkAccount(UUID accountId) {
        if (accountId != null && !repository.accountExists(accountId)) {
            throw ApiException.validation("VALIDATION_ERROR", "Аккаунт не найден",
                    new HashMap<>(Map.of("accountId", "Аккаунт не найден")));
        }
    }

    private static ApiException authorNotFound() {
        return ApiException.notFound("AUTHOR_NOT_FOUND", "Автор не найден");
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.unlinkAccount(event.accountId());
    }
}
