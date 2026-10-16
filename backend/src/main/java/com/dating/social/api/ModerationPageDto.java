package com.dating.social.api;

import java.util.List;

public record ModerationPageDto(List<ModerationReportDto> items, int page, int limit, long totalElements) {
}
