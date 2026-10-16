package com.dating.admin.api;

import com.dating.admin.application.AdminContentService;
import com.dating.common.security.AuthenticatedUser;
import com.dating.profiles.application.ReferenceDataAdminService.DeleteOutcome;
import com.dating.profiles.application.ReferenceDataAdminService.GoalFields;
import com.dating.profiles.application.ReferenceDataAdminService.TagFields;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.CategoryRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.GoalRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.TagRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Communication cards (tags), tag categories and relationship goals (CONTENT_EDITOR, ADMIN).
 * Codes are set on creation and never change. DELETE of something profiles use hides it instead.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminContentController {

    static final String UPPER_CODE = "^[A-Z][A-Z0-9_]{1,39}$";
    static final String UPPER_CODE_MESSAGE = "Код: латинские заглавные буквы, цифры и _ (2..40 символов)";

    public record CreateTagRequest(
            @NotBlank @Pattern(regexp = UPPER_CODE, message = UPPER_CODE_MESSAGE) String code,
            @NotBlank(message = "Укажите категорию") String category,
            @NotBlank(message = "Укажите название") @Size(max = 60) String label,
            @NotBlank(message = "Укажите описание") @Size(max = 255) String description,
            @Min(0) @Max(100_000) Integer sortOrder,
            Boolean active) {
    }

    public record UpdateTagRequest(
            @NotBlank(message = "Укажите категорию") String category,
            @NotBlank(message = "Укажите название") @Size(max = 60) String label,
            @NotBlank(message = "Укажите описание") @Size(max = 255) String description,
            @Min(0) @Max(100_000) Integer sortOrder,
            Boolean active) {
    }

    public record CreateCategoryRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{1,29}$",
                    message = "Код: латинские строчные буквы, цифры и _ (2..30 символов)") String code,
            @NotBlank(message = "Укажите название") @Size(max = 50) String label,
            @Min(0) @Max(100_000) Integer sortOrder) {
    }

    public record UpdateCategoryRequest(
            @NotBlank(message = "Укажите название") @Size(max = 50) String label,
            @Min(0) @Max(100_000) Integer sortOrder) {
    }

    /**
     * @param positions card code → place on the scale (1..100; smaller = closer to scaleMinLabel)
     */
    public record CategoryScaleRequest(
            @NotNull(message = "Укажите, шкала ли это") Boolean scale,
            @Size(max = 60) String scaleMinLabel,
            @Size(max = 60) String scaleMaxLabel,
            java.util.Map<String, @NotNull @Min(1) @Max(100) Integer> positions) {
    }

    public record CreateGoalRequest(
            @NotBlank @Pattern(regexp = UPPER_CODE, message = UPPER_CODE_MESSAGE) String code,
            @NotBlank(message = "Укажите название") @Size(max = 60) String label,
            @Min(0) @Max(100_000) Integer sortOrder,
            Boolean active) {
    }

    public record UpdateGoalRequest(
            @NotBlank(message = "Укажите название") @Size(max = 60) String label,
            @Min(0) @Max(100_000) Integer sortOrder,
            Boolean active) {
    }

    /**
     * @param result DELETED, or DEACTIVATED when profiles use it (hidden, can be shown again with PUT active=true)
     */
    public record DeleteResponse(DeleteOutcome result) {
    }

    private final AdminContentService service;

    public AdminContentController(AdminContentService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- tags

    @GetMapping("/tags")
    public List<TagRow> tags(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.tags(user.accountId());
    }

    @PostMapping("/tags")
    public ResponseEntity<TagRow> createTag(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody CreateTagRequest request) {
        TagFields fields = new TagFields(request.category().strip(), request.label().strip(),
                request.description().strip(), orZero(request.sortOrder()), orTrue(request.active()));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTag(user.accountId(), request.code(), fields));
    }

    @PutMapping("/tags/{code}")
    public TagRow updateTag(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                            @Valid @RequestBody UpdateTagRequest request) {
        TagFields fields = new TagFields(request.category().strip(), request.label().strip(),
                request.description().strip(), orZero(request.sortOrder()), orTrue(request.active()));
        return service.updateTag(user.accountId(), code, fields);
    }

    @DeleteMapping("/tags/{code}")
    public DeleteResponse deleteTag(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        return new DeleteResponse(service.deleteTag(user.accountId(), code));
    }

    // ---------------------------------------------------------------- categories

    @GetMapping("/tag-categories")
    public List<CategoryRow> categories(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.categories(user.accountId());
    }

    @PostMapping("/tag-categories")
    public ResponseEntity<CategoryRow> createCategory(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(user.accountId(), request.code(),
                request.label().strip(), orZero(request.sortOrder())));
    }

    @PutMapping("/tag-categories/{code}")
    public CategoryRow updateCategory(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                      @Valid @RequestBody UpdateCategoryRequest request) {
        return service.updateCategory(user.accountId(), code, request.label().strip(), orZero(request.sortOrder()));
    }

    /** Orders the category's cards on a scale for compatibility (or turns the scale off). */
    @PutMapping("/tag-categories/{code}/scale")
    public CategoryRow setCategoryScale(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                        @Valid @RequestBody CategoryScaleRequest request) {
        return service.setCategoryScale(user.accountId(), code, request.scale(), blankToNull(request.scaleMinLabel()),
                blankToNull(request.scaleMaxLabel()), request.positions() == null ? java.util.Map.of() : request.positions());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @DeleteMapping("/tag-categories/{code}")
    public ResponseEntity<Void> deleteCategory(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        service.deleteCategory(user.accountId(), code);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- goals

    @GetMapping("/goals")
    public List<GoalRow> goals(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.goals(user.accountId());
    }

    @PostMapping("/goals")
    public ResponseEntity<GoalRow> createGoal(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody CreateGoalRequest request) {
        GoalFields fields = new GoalFields(request.label().strip(), orZero(request.sortOrder()), orTrue(request.active()));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGoal(user.accountId(), request.code(), fields));
    }

    @PutMapping("/goals/{code}")
    public GoalRow updateGoal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                              @Valid @RequestBody UpdateGoalRequest request) {
        GoalFields fields = new GoalFields(request.label().strip(), orZero(request.sortOrder()), orTrue(request.active()));
        return service.updateGoal(user.accountId(), code, fields);
    }

    @DeleteMapping("/goals/{code}")
    public DeleteResponse deleteGoal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        return new DeleteResponse(service.deleteGoal(user.accountId(), code));
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    static boolean orTrue(Boolean value) {
        return value == null || value;
    }
}
