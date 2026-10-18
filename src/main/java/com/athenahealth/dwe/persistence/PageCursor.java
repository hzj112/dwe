package com.athenahealth.dwe.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Stateless continuation tokens bound to a collection and its filters; they are not authorization tokens. */
@Component
public class PageCursor {
    private final JsonMapper json;

    public PageCursor(JsonMapper json) { this.json = json; }

    /** Position uses only immutable ordering fields, so updates to status cannot move the page boundary. */
    public record Position(Instant time, String key, long number) { }
    private record Token(int version, String resource, String filter, Position position) { }

    public String encode(String resource, String filter, Position position) {
        return encode(new Token(1, resource, filter, position));
    }

    /** Accept only the canonical format we issue, including its version, resource, and filter binding. */
    public Position decode(String cursor, String resource, String filter) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 8192) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            Token token = json.readValue(bytes, Token.class);
            if (token.version() != 1 || !resource.equals(token.resource()) || !filter.equals(token.filter())
                    || token.position() == null || !encode(token).equals(cursor)) {
                throw new IllegalArgumentException();
            }
            return token.position();
        } catch (RuntimeException e) {
            throw new InvalidQueryException("Invalid cursor or cursor does not match this collection and its filters");
        }
    }

    private String encode(Token token) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsString(token).getBytes(StandardCharsets.UTF_8));
    }

    /** Invalid query parameters are client errors, rather than database or unexpected server failures. */
    public static final class InvalidQueryException extends RuntimeException {
        public InvalidQueryException(String message) { super(message); }
    }
}
