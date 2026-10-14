package com.dating.media.application;

import com.dating.common.errors.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path storageDirectory;

    public FileStorageService(@Value("${app.media.storage-path:./data/media}") String storagePath) {
        this.storageDirectory = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize local storage directory", e);
        }
    }

    public String saveFile(InputStream inputStream, String extension) {
        String randomKey = UUID.randomUUID().toString().replace("-", "") + extension;
        Path targetLocation = this.storageDirectory.resolve(randomKey).normalize();

        // Path traversal defense
        if (!targetLocation.startsWith(this.storageDirectory)) {
            throw ApiException.badRequest("INVALID_PATH", "Некорректное имя пути файла");
        }

        try {
            Files.copy(inputStream, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            return randomKey;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    public byte[] loadFile(String storageKey) {
        Path filePath = this.storageDirectory.resolve(storageKey).normalize();
        if (!filePath.startsWith(this.storageDirectory) || !Files.exists(filePath)) {
            throw ApiException.notFound("Файл не найден");
        }

        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
        }
    }

    public void deleteFile(String storageKey) {
        Path filePath = this.storageDirectory.resolve(storageKey).normalize();
        if (filePath.startsWith(this.storageDirectory)) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ignored) {
            }
        }
    }
}
