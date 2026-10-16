package com.dating.profiles.application;

import com.dating.common.errors.ApiException;
import com.dating.profiles.persistence.ReferenceDataAdminRepository;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.CategoryRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.GoalRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.TagRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Editing communication cards (tags), their categories and relationship goals without migrations.
 * Codes are immutable: clients, saved filters and analytics refer to them. Something that profiles
 * already use is hidden (active = false) instead of deleted; hidden items disappear from the public
 * lists and from profiles, and can be shown again.
 * <p>
 * Role checks and the audit log are done by the admin API around these calls.
 */
@Service
public class ReferenceDataAdminService {

    /** What a delete request did. */
    public enum DeleteOutcome {
        DELETED,
        DEACTIVATED
    }

    public record TagFields(String category, String label, String description, int sortOrder, boolean active) {
    }

    public record GoalFields(String label, int sortOrder, boolean active) {
    }

    private final ReferenceDataAdminRepository repository;

    public ReferenceDataAdminService(ReferenceDataAdminRepository repository) {
        this.repository = repository;
    }

    // ---------------------------------------------------------------- tags

    @Transactional(readOnly = true)
    public List<TagRow> tags() {
        return repository.tags();
    }

    @Transactional(readOnly = true)
    public Optional<TagRow> findTag(String code) {
        return repository.tag(code);
    }

    @Transactional(readOnly = true)
    public TagRow requireTag(String code) {
        return repository.tag(code).orElseThrow(() -> ApiException.notFound("TAG_NOT_FOUND", "Карточка не найдена: " + code));
    }

    @Transactional
    public TagRow createTag(String code, TagFields fields) {
        requireCategory(fields.category());
        if (!repository.insertTag(code, fields.category(), fields.label(), fields.description(), fields.sortOrder(),
                fields.active())) {
            throw ApiException.conflict("TAG_EXISTS", "Карточка с таким кодом уже есть: " + code);
        }
        return requireTag(code);
    }

    @Transactional
    public TagRow updateTag(String code, TagFields fields) {
        requireTag(code);
        requireCategory(fields.category());
        repository.updateTag(code, fields.category(), fields.label(), fields.description(), fields.sortOrder(),
                fields.active());
        return requireTag(code);
    }

    @Transactional
    public DeleteOutcome deleteTag(String code) {
        if (!repository.lock("communication_tags", code)) {
            throw ApiException.notFound("TAG_NOT_FOUND", "Карточка не найдена: " + code);
        }
        TagRow tag = requireTag(code);
        if (tag.usageCount() > 0) {
            repository.updateTag(code, tag.category(), tag.label(), tag.description(), tag.sortOrder(), false);
            return DeleteOutcome.DEACTIVATED;
        }
        repository.deleteTag(code);
        return DeleteOutcome.DELETED;
    }

    // ---------------------------------------------------------------- categories

    @Transactional(readOnly = true)
    public List<CategoryRow> categories() {
        return repository.categories();
    }

    @Transactional(readOnly = true)
    public CategoryRow requireCategory(String code) {
        return repository.category(code).orElseThrow(() -> ApiException.validation("UNKNOWN_CATEGORY",
                "Категория не найдена: " + code, java.util.Map.of("category", "Неизвестная категория")));
    }

    @Transactional(readOnly = true)
    public Optional<CategoryRow> findCategory(String code) {
        return repository.category(code);
    }

    @Transactional
    public CategoryRow createCategory(String code, String label, int sortOrder) {
        if (!repository.insertCategory(code, label, sortOrder)) {
            throw ApiException.conflict("CATEGORY_EXISTS", "Категория с таким кодом уже есть: " + code);
        }
        return repository.category(code).orElseThrow();
    }

    @Transactional
    public CategoryRow updateCategory(String code, String label, int sortOrder) {
        repository.category(code).orElseThrow(this::categoryNotFound);
        repository.updateCategory(code, label, sortOrder);
        return repository.category(code).orElseThrow();
    }

    /**
     * Orders a category's cards on a scale (or turns the scale off). Every listed card must belong to the category;
     * a scale needs both end labels and at least two placed cards.
     *
     * @throws ApiException 404 CATEGORY_NOT_FOUND, 400 VALIDATION_ERROR
     */
    @Transactional
    public CategoryRow setScale(String code, boolean scale, String minLabel, String maxLabel,
                                java.util.Map<String, Integer> positions) {
        if (!repository.lock("tag_categories", code)) {
            throw categoryNotFound();
        }
        java.util.Map<String, Integer> placed = scale ? positions : java.util.Map.of();
        if (scale && (minLabel == null || maxLabel == null || placed.size() < 2)) {
            throw ApiException.validation("VALIDATION_ERROR", "Шкале нужны подписи концов и хотя бы две карточки",
                    java.util.Map.of("positions", "Расставьте хотя бы две карточки и подпишите концы шкалы"));
        }
        java.util.Set<String> ofCategory = repository.tags().stream()
                .filter(t -> t.category().equals(code)).map(TagRow::code).collect(java.util.stream.Collectors.toSet());
        placed.keySet().stream().filter(tag -> !ofCategory.contains(tag)).findFirst().ifPresent(tag -> {
            throw ApiException.validation("VALIDATION_ERROR", "Карточка не из этой категории: " + tag,
                    java.util.Map.of("positions", "Карточка " + tag + " не из категории " + code));
        });
        repository.setScale(code, scale, scale ? minLabel : null, scale ? maxLabel : null, placed);
        return repository.category(code).orElseThrow();
    }

    /**
     * A category is plain grouping data, so it is only deleted when empty (hide its tags instead).
     */
    @Transactional
    public void deleteCategory(String code) {
        if (!repository.lock("tag_categories", code)) {
            throw categoryNotFound();
        }
        if (repository.category(code).orElseThrow().tagCount() > 0) {
            throw ApiException.conflict("CATEGORY_IN_USE", "В категории есть карточки: перенесите или удалите их");
        }
        repository.deleteCategory(code);
    }

    private ApiException categoryNotFound() {
        return ApiException.notFound("CATEGORY_NOT_FOUND", "Категория не найдена");
    }

    // ---------------------------------------------------------------- goals

    @Transactional(readOnly = true)
    public List<GoalRow> goals() {
        return repository.goals();
    }

    @Transactional(readOnly = true)
    public Optional<GoalRow> findGoal(String code) {
        return repository.goal(code);
    }

    @Transactional(readOnly = true)
    public GoalRow requireGoal(String code) {
        return repository.goal(code).orElseThrow(() -> ApiException.notFound("GOAL_NOT_FOUND", "Цель не найдена: " + code));
    }

    @Transactional
    public GoalRow createGoal(String code, GoalFields fields) {
        if (!repository.insertGoal(code, fields.label(), fields.sortOrder(), fields.active())) {
            throw ApiException.conflict("GOAL_EXISTS", "Цель с таким кодом уже есть: " + code);
        }
        return requireGoal(code);
    }

    @Transactional
    public GoalRow updateGoal(String code, GoalFields fields) {
        requireGoal(code);
        repository.updateGoal(code, fields.label(), fields.sortOrder(), fields.active());
        return requireGoal(code);
    }

    /**
     * Goals used in profiles or in saved excluded goals are hidden instead of deleted.
     */
    @Transactional
    public DeleteOutcome deleteGoal(String code) {
        if (!repository.lock("relationship_goals", code)) {
            throw ApiException.notFound("GOAL_NOT_FOUND", "Цель не найдена: " + code);
        }
        GoalRow goal = requireGoal(code);
        if (goal.usageCount() > 0) {
            repository.updateGoal(code, goal.label(), goal.sortOrder(), false);
            return DeleteOutcome.DEACTIVATED;
        }
        repository.deleteGoal(code);
        return DeleteOutcome.DELETED;
    }
}
