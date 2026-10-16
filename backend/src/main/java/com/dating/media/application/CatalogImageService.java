package com.dating.media.application;

import com.dating.common.errors.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Images of catalog items (frames, stickers, wallpapers). Same pipeline as profile photos: the type is
 * detected from the bytes (JPEG/PNG/WebP), metadata is stripped, the file is removed if the transaction
 * rolls back. Unlike photos they are public (cosmetics are visible to everyone), so the URL is not signed.
 * An image is never changed in place: a new upload gets a new id, so responses can be cached forever.
 */
@Service
public class CatalogImageService {

    public static final String URL_PREFIX = "/api/v1/catalog/images/";

    public record ImageContent(Path path, String mimeType) {
    }

    private final FileStorageService fileStorage;
    private final MediaProperties properties;
    private final JdbcClient jdbc;

    public CatalogImageService(FileStorageService fileStorage, MediaProperties properties, JdbcClient jdbc) {
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * @return id of the stored image; its public URL is {@link #url(UUID)}
     */
    @Transactional
    public UUID store(MultipartFile file, UUID uploadedBy) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Файл пустой");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw ApiException.payloadTooLarge("FILE_TOO_LARGE", "Размер файла превышает 5 МБ");
        }
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
        ImageFormat format = ImageFormat.detect(original).orElseThrow(() ->
                ApiException.unsupportedMediaType("Поддерживаются только изображения JPEG, PNG и WebP"));
        byte[] cleaned;
        try {
            cleaned = ImageMetadataStripper.strip(original, format);
        } catch (IllegalArgumentException e) {
            throw ApiException.unsupportedMediaType("Файл повреждён или не является изображением");
        }
        UUID id = UUID.randomUUID();
        String storageKey = fileStorage.save(cleaned, format.extension());
        jdbc.sql("""
                        INSERT INTO catalog_images (id, storage_key, mime_type, size_bytes, uploaded_by)
                        VALUES (:id, :key, :mime, :size, :by)
                        """)
                .param("id", id)
                .param("key", storageKey)
                .param("mime", format.mimeType())
                .param("size", cleaned.length)
                .param("by", uploadedBy)
                .update();
        return id;
    }

    public static String url(UUID imageId) {
        return URL_PREFIX + imageId;
    }

    @Transactional(readOnly = true)
    public ImageContent load(UUID imageId) {
        return jdbc.sql("SELECT storage_key, mime_type FROM catalog_images WHERE id = :id")
                .param("id", imageId)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                .optional()
                .flatMap(row -> fileStorage.find(row[0]).map(path -> new ImageContent(path, row[1])))
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "Картинка не найдена"));
    }
}
