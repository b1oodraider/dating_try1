package com.dating.admin.application;

import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.economy.application.CatalogAdminService;
import com.dating.economy.application.CatalogAdminService.AuthorFields;
import com.dating.economy.application.CatalogAdminService.NewItem;
import com.dating.economy.domain.Author;
import com.dating.economy.domain.CatalogEntry;
import com.dating.economy.persistence.CatalogRepository.ItemChanges;
import com.dating.media.application.CatalogImageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Catalog items and item images (CONTENT_EDITOR, ADMIN) and authors (read: editors, write: ADMIN only,
 * because authors carry the revenue share). Every write is audited.
 */
@Service
public class AdminCatalogService {

    private final RoleAccess roleAccess;
    private final CatalogAdminService catalog;
    private final CatalogImageService images;
    private final AdminAuditLog auditLog;

    public AdminCatalogService(RoleAccess roleAccess, CatalogAdminService catalog, CatalogImageService images,
                               AdminAuditLog auditLog) {
        this.roleAccess = roleAccess;
        this.catalog = catalog;
        this.images = images;
        this.auditLog = auditLog;
    }

    // ---------------------------------------------------------------- items

    @Transactional(readOnly = true)
    public List<CatalogEntry> items(UUID actor, String kind, String category) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        return catalog.items(kind, category);
    }

    @Transactional(readOnly = true)
    public CatalogEntry item(UUID actor, String code) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        return catalog.requireItem(code);
    }

    @Transactional
    public CatalogEntry create(UUID actor, NewItem item) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        CatalogEntry created = catalog.create(item);
        auditLog.record(actor, "CREATE", "CATALOG_ITEM", created.code(), null, created);
        return created;
    }

    @Transactional
    public CatalogEntry update(UUID actor, String code, ItemChanges changes) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        CatalogEntry before = catalog.requireItem(code);
        CatalogEntry after = catalog.update(code, changes);
        auditLog.record(actor, "UPDATE", "CATALOG_ITEM", code, before, after);
        return after;
    }

    /**
     * Stores the image (metadata stripped) and points attributes.imageUrl at it. If anything fails,
     * the transaction rolls back and the stored file is removed.
     */
    @Transactional
    public CatalogEntry uploadImage(UUID actor, String code, MultipartFile file) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        CatalogEntry before = catalog.requireItem(code);
        UUID imageId = images.store(file, actor);
        CatalogEntry after = catalog.setImageUrl(code, CatalogImageService.url(imageId));
        auditLog.record(actor, "IMAGE_UPLOAD", "CATALOG_ITEM", code, before, after);
        return after;
    }

    // ---------------------------------------------------------------- authors

    @Transactional(readOnly = true)
    public List<Author> authors(UUID actor) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        return catalog.authors();
    }

    @Transactional
    public Author createAuthor(UUID actor, AuthorFields fields) {
        roleAccess.require(actor, Role.ADMIN);
        Author created = catalog.createAuthor(fields);
        auditLog.record(actor, "CREATE", "AUTHOR", created.id().toString(), null, created);
        return created;
    }

    @Transactional
    public Author updateAuthor(UUID actor, UUID id, AuthorFields fields) {
        roleAccess.require(actor, Role.ADMIN);
        Author before = catalog.requireAuthor(id);
        Author after = catalog.updateAuthor(id, fields);
        auditLog.record(actor, "UPDATE", "AUTHOR", id.toString(), before, after);
        return after;
    }

    @Transactional
    public void deleteAuthor(UUID actor, UUID id) {
        roleAccess.require(actor, Role.ADMIN);
        Author before = catalog.requireAuthor(id);
        catalog.deleteAuthor(id);
        auditLog.record(actor, "DELETE", "AUTHOR", id.toString(), before, null);
    }
}
