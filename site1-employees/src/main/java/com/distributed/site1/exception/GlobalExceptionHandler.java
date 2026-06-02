package com.distributed.site1.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — Site 1
 *
 * Catches any unhandled exception from all @RestController endpoints
 * and returns a consistent JSON error response instead of Spring's default HTML error page.
 *
 * Priority (most specific → most general):
 *   1. ResourceAccessException  → Site 2 network failure (connection refused / timeout)
 *   2. NullPointerException     → Defensive catch for unexpected null (e.g., bad Site 2 response)
 *   3. Exception                → Catch-all for anything else
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TIMESTAMP_FMT = "yyyy-MM-dd HH:mm:ss";

    // ── 1. Site 2 unreachable ─────────────────────────────────────────────────
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleSite2Down(ResourceAccessException ex) {
        System.err.println("[GlobalHandler] Site 2 unreachable: " + ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",        "FAILED");
        body.put("failure_type",  "NETWORK_FAILURE");
        body.put("error",         "Site 2 (port 8082) is unreachable — connection refused or timeout");
        body.put("detail",        ex.getMessage());
        body.put("impact",        "All operations requiring Site 2 are unavailable. Site 1 data is intact.");
        body.put("recovery_hint", "Start Site 2: cd site2-assignments && mvn spring-boot:run");
        body.put("timestamp",     now());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    // ── 2. Null pointer — e.g. Site 2 returned unexpected JSON shape ──────────
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException ex) {
        System.err.println("[GlobalHandler] NullPointerException: " + ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",        "FAILED");
        body.put("failure_type",  "UNEXPECTED_NULL");
        body.put("error",         "Unexpected null value — Site 2 may have returned an incomplete response");
        body.put("detail",        ex.getMessage());
        body.put("recovery_hint", "Check that Site 2 is fully started and its dataset is loaded correctly");
        body.put("timestamp",     now());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── 3. ClassCastException — defensive: guard against malformed responses ──
    @ExceptionHandler(ClassCastException.class)
    public ResponseEntity<Map<String, Object>> handleClassCast(ClassCastException ex) {
        System.err.println("[GlobalHandler] ClassCastException: " + ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",        "FAILED");
        body.put("failure_type",  "RESPONSE_PARSE_ERROR");
        body.put("error",         "Site 2 returned an unexpected response format");
        body.put("detail",        ex.getMessage());
        body.put("recovery_hint", "Verify Site 2 version matches Site 1 — check both pom.xml versions");
        body.put("timestamp",     now());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── 4. Catch-all ──────────────────────────────────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        System.err.println("[GlobalHandler] Unhandled exception: " + ex.getClass().getSimpleName()
                + " — " + ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",        "FAILED");
        body.put("failure_type",  "INTERNAL_ERROR");
        body.put("exception",     ex.getClass().getSimpleName());
        body.put("error",         ex.getMessage());
        body.put("recovery_hint", "Check Site 1 and Site 2 logs for details");
        body.put("timestamp",     now());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(TIMESTAMP_FMT));
    }
}
