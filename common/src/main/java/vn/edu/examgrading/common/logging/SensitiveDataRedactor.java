package vn.edu.examgrading.common.logging;

import java.util.regex.Pattern;

/** Redacts common secrets before any log sink receives a message. */
public final class SensitiveDataRedactor {
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(authorization|password|token|signature|presigned|x-amz-[^= ]+)=?[^,\\s]*");
    private SensitiveDataRedactor() { }
    public static String redact(String input) { return input == null ? null : SENSITIVE.matcher(input).replaceAll("$1=[REDACTED]"); }
}
