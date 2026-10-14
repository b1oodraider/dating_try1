package com.dating.media.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Local file storage for photos. Files and DB rows are kept consistent via transaction callbacks:
 * a new file is removed if the transaction rolls back, a deleted file is removed only after commit.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path storageDirectory;

    public FileStorageService(MediaProperties properties) {
        this.storageDirectory = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not initialize media storage directory " + storageDirectory, e);
        }
        if (!Files.isWritable(this.storageDirectory)) {
            throw new IllegalStateException("Media storage directory is not writable: " + storageDirectory);
        }
    }

    /**
     * Writes the file and schedules its removal if the surrounding transaction rolls back.
     */
    public String save(byte[] content, String extension) {
        String key = UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = resolveKey(key).orElseThrow();
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteQuietly(key);
                    }
                }
            });
        }
        return key;
    }

    public Optional<Path> find(String key) {
        return resolveKey(key).filter(Files::isRegularFile);
    }

    /**
     * Deletes the files once the surrounding transaction has committed (immediately if there is none).
     */
    public void deleteAfterCommit(Collection<String> keys) {
        List<String> copy = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    copy.forEach(FileStorageService.this::deleteQuietly);
                }
            });
        } else {
            copy.forEach(this::deleteQuietly);
        }
    }

    private void deleteQuietly(String key) {
        resolveKey(key).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Could not delete media file {}: {}", key, e.getMessage());
            }
        });
    }

    private Optional<Path> resolveKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Path path = storageDirectory.resolve(key).normalize();
        // Path traversal defense: keys come from the DB, but never trust them blindly.
        return path.startsWith(storageDirectory) && !path.equals(storageDirectory) ? Optional.of(path) : Optional.empty();
    }
}
