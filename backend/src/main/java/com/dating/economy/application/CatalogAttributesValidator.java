package com.dating.economy.application;

import com.dating.economy.domain.ItemKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks item attributes against the shapes clients render (docs/api-contract.md, "Attributes by category"),
 * so an editor cannot publish a frame without a color that breaks every feed card.
 * Extra keys are allowed (clients ignore unknown keys); unknown categories accept any object.
 */
public final class CatalogAttributesValidator {

    public static final String IMAGE_URL_PREFIX = "/api/v1/catalog/images/";

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern TOKEN = Pattern.compile("^[a-z0-9_-]{1,40}$");
    private static final Pattern IMAGE_URL = Pattern.compile(
            "^" + Pattern.quote(IMAGE_URL_PREFIX) + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int MAX_STICKERS = 100;

    private CatalogAttributesValidator() {
    }

    /**
     * @return field errors keyed "attributes.&lt;key&gt;"; empty if valid
     */
    public static Map<String, String> validate(ItemKind kind, String category, Map<String, Object> attributes) {
        Errors errors = new Errors(attributes);
        if (attributes.containsKey("imageUrl")) {
            errors.string("imageUrl", IMAGE_URL, "ссылка на загруженную картинку предмета", true);
        }
        if (attributes.containsKey("stub") && !(attributes.get("stub") instanceof Boolean)) {
            errors.put("stub", "Должно быть true или false");
        }
        if (kind == ItemKind.CONSUMABLE) {
            errors.string("icon", TOKEN, "код иконки (латиница, цифры, _ и -)", true);
            return errors.map;
        }
        switch (category) {
            case "PHOTO_FRAME" -> {
                errors.color("borderColor");
                errors.number("borderWidth", 1, 20);
                if (attributes.containsKey("glow") && !(attributes.get("glow") instanceof Boolean)) {
                    errors.put("glow", "Должно быть true или false");
                }
            }
            case "PROFILE_STATUS" -> {
                errors.text("emoji", 1, 16);
                errors.text("label", 1, 30);
                errors.color("color");
            }
            case "CHAT_WALLPAPER" -> {
                errors.color("background");
                errors.color("accent");
                errors.string("pattern", TOKEN, "код узора (латиница, цифры, _ и -)", false);
            }
            case "STICKER_PACK" -> {
                errors.text("emoji", 1, 16);
                Object stickers = attributes.get("stickers");
                if (!(stickers instanceof List<?> list) || list.isEmpty() || list.size() > MAX_STICKERS) {
                    errors.put("stickers", "Нужен непустой список кодов стикеров (до " + MAX_STICKERS + ")");
                } else if (!list.stream().allMatch(s -> s instanceof String code && TOKEN.matcher(code).matches())) {
                    errors.put("stickers", "Коды стикеров: латиница, цифры, _ и -");
                }
            }
            default -> {
                // New category: shape is up to the clients that support it.
            }
        }
        return errors.map;
    }

    private static final class Errors {
        private final Map<String, Object> attributes;
        private final Map<String, String> map = new LinkedHashMap<>();

        Errors(Map<String, Object> attributes) {
            this.attributes = attributes;
        }

        void put(String key, String message) {
            map.putIfAbsent("attributes." + key, message);
        }

        void color(String key) {
            string(key, COLOR, "цвет в формате #RRGGBB", true);
        }

        void string(String key, Pattern pattern, String what, boolean required) {
            Object value = attributes.get(key);
            if (value == null) {
                if (required) {
                    put(key, "Обязательное поле: " + what);
                }
            } else if (!(value instanceof String s) || !pattern.matcher(s).matches()) {
                put(key, "Ожидается " + what);
            }
        }

        void text(String key, int min, int max) {
            Object value = attributes.get(key);
            if (!(value instanceof String s) || s.isBlank() || s.length() < min || s.length() > max) {
                put(key, "Обязательная строка длиной " + min + ".." + max);
            }
        }

        void number(String key, int min, int max) {
            Object value = attributes.get(key);
            if (!(value instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
                put(key, "Обязательное число " + min + ".." + max);
            }
        }
    }
}
