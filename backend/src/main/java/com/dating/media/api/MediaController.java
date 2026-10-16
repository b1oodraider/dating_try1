package com.dating.media.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.media.application.MediaService;
import com.dating.profiles.api.PhotoDto;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping(value = "/me/profile/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoDto> uploadPhoto(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(mediaService.uploadPhoto(user.accountId(), file));
    }

    @GetMapping("/me/profile/photos")
    public ResponseEntity<List<PhotoDto>> getMyPhotos(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(mediaService.getMyPhotos(user.accountId()));
    }

    @PatchMapping("/me/profile/photos/{photoId}")
    public ResponseEntity<Void> setPrimaryPhoto(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID photoId
    ) {
        mediaService.setPrimaryPhoto(user.accountId(), photoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/profile/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID photoId
    ) {
        mediaService.deletePhoto(user.accountId(), photoId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Public endpoint guarded by the URL signature (see PhotoUrlSigner), so it works in &lt;img src&gt;.
     */
    @GetMapping("/media/photos/{photoId}")
    public ResponseEntity<Resource> getPhoto(
            @PathVariable UUID photoId,
            @RequestParam("exp") long expiresAt,
            @RequestParam("sig") String signature
    ) {
        MediaService.PhotoContent content = mediaService.loadPhoto(photoId, expiresAt, signature);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(content.cacheSeconds())).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(new FileSystemResource(content.path()));
    }
}
