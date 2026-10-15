package com.dating.media.api;

import com.dating.media.application.CatalogImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Public images of catalog items (uploads go through the admin API). Immutable by id, cached for a year.
 */
@RestController
public class CatalogImageController {

    private final CatalogImageService catalogImageService;

    public CatalogImageController(CatalogImageService catalogImageService) {
        this.catalogImageService = catalogImageService;
    }

    @GetMapping("/api/v1/catalog/images/{imageId}")
    public ResponseEntity<Resource> image(@PathVariable UUID imageId) {
        CatalogImageService.ImageContent content = catalogImageService.load(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(new FileSystemResource(content.path()));
    }
}
