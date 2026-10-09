package vn.edu.examgrading.common.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque cursor codec; service code controls the underlying cursor value. */
public final class CursorCodec {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    private CursorCodec() { }
    public static int normalizeLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be 1..200");
        return limit;
    }
    public static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    public static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
