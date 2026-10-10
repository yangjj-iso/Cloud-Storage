package com.cloudchunk.core.knowledge;

import java.util.Locale;
import java.util.Set;

/** Deliberately bounded M1: no OCR, archive traversal, arbitrary MIME parser or remote model. */
public final class KnowledgePolicy {
    public static final String PARSER_VERSION = "tika-3.3.2-blocks-v1";
    public static final int MAX_CHARS = 262144;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final int MAX_ATTEMPTS = 3;
    public static final Set<String> MIME_TYPES = Set.of("text/plain", "text/markdown", "text/x-markdown",
            "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private KnowledgePolicy() {}
    public static String mime(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
    public static boolean supported(String value) { return MIME_TYPES.contains(mime(value)); }
}
