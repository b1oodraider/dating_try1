package com.dating.media.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.media.application.MediaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    public ResponseEntity<PhotoResponseDto> uploadPhoto(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file
    ) {
        PhotoResponseDto response = mediaService.uploadPhoto(user.accountId(), file);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me/profile/photos")
    public ResponseEntity<List<PhotoResponseDto>> getMyPhotos(@AuthenticationPrincipal AuthenticatedUser user) {
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

    @GetMapping("/profiles/{profileId}/photos/{photoId}")
    public ResponseEntity<byte[]> getPhotoBinary(
            @PathVariable UUID profileId,
            @PathVariable UUID photoId
    ) {
        byte[] data = mediaService.downloadPhoto(profileId, photoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body(data);
    }
}
