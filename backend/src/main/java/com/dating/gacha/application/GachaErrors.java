package com.dating.gacha.application;

import com.dating.common.errors.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Gacha error codes (messages are user-facing, in Russian).
 */
final class GachaErrors {

    private GachaErrors() {
    }

    static ApiException bannerNotFound(String code) {
        return ApiException.notFound("BANNER_NOT_FOUND", "Баннер не найден: " + code);
    }

    static ApiException bannerNotActive() {
        return new ApiException("BANNER_NOT_ACTIVE", "Этот баннер сейчас недоступен", HttpStatus.CONFLICT);
    }

    static ApiException invalidPullCount(int multiPullSize) {
        return ApiException.badRequest("INVALID_PULL_COUNT", "Можно крутить 1 или " + multiPullSize + " раз");
    }

    static ApiException idempotencyKeyReused() {
        return new ApiException("IDEMPOTENCY_KEY_REUSED", "Этот ключ запроса уже использован для другой крутки",
                HttpStatus.CONFLICT);
    }

    static ApiException entryNotFound(String bannerCode, String entryId) {
        return ApiException.notFound("POOL_ENTRY_NOT_FOUND", "В баннере " + bannerCode + " нет позиции " + entryId);
    }
}
