package com.athenahealth.dwe.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Validates the portable token format separately from the PostgreSQL ordering tests. */
class PageCursorTest {
    private final PageCursor cursors = new PageCursor(JsonMapper.builder().build());
    private final PageCursor.Position position = new PageCursor.Position(Instant.parse("2026-01-01T00:00:00.123456Z"), "a", 7);

    /** Microseconds and a long counter must survive encoding without rounding or truncation. */
    @Test
    void roundTripsImmutablePositionAndSupportsAbsentCursor() {
        var token = cursors.encode("executions/id/attempts", "filter", position);
        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(cursors.decode(token, "executions/id/attempts", "filter")).isEqualTo(position);
        assertThat(cursors.decode(null, "executions", "")).isNull();
        var large = new PageCursor.Position(null, null, Long.MAX_VALUE);
        assertThat(cursors.decode(cursors.encode("x", "", large), "x", "")).isEqualTo(large);
    }

    /** Tokens cannot be carried to another execution, collection, or filter selection. */
    @Test
    void rejectsDifferentResourceAndFilters() {
        String token = cursors.encode("resource", "FAILED", position);
        assertThatThrownBy(() -> cursors.decode(token, "other", "FAILED")).isInstanceOf(PageCursor.InvalidQueryException.class);
        assertThatThrownBy(() -> cursors.decode(token, "resource", "RUNNING")).isInstanceOf(PageCursor.InvalidQueryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-token!", "e30", "bnVsbA", "W10", " ", "===="})
    void rejectsMalformedOrIncompleteTokens(String token) {
        assertThatThrownBy(() -> cursors.decode(token, "resource", ""))
                .isInstanceOf(PageCursor.InvalidQueryException.class);
    }

    /** Future versions, unknown fields, coercions, and missing positions fail rather than guessing a format. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":2,\"resource\":\"r\",\"filter\":\"\",\"position\":{\"time\":null,\"key\":null,\"number\":1}}",
            "{\"version\":1,\"resource\":\"r\",\"filter\":\"\",\"position\":null}",
            "{\"version\":\"1\",\"resource\":\"r\",\"filter\":\"\",\"position\":{\"time\":null,\"key\":null,\"number\":1}}",
            "{\"version\":1,\"resource\":\"r\",\"filter\":\"\",\"position\":{\"time\":null,\"key\":null,\"number\":1},\"extra\":true}"
    })
    void rejectsUnsupportedOrNoncanonicalJson(String payload) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cursors.decode(token, "r", "")).isInstanceOf(PageCursor.InvalidQueryException.class);
    }

    @Test
    void rejectsOversizedTokens() {
        assertThatThrownBy(() -> cursors.decode("a".repeat(8193), "resource", ""))
                .isInstanceOf(PageCursor.InvalidQueryException.class);
    }
}
