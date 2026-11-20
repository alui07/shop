package dev.shop.api.admin;

import java.util.List;

record AuditPage(List<AuditEvent> items, int page, int size, long totalItems, int totalPages) {}
