package com.cloudchunk.infra.inbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Opt-in namespace for new subscribers. Existing consumers retain their persisted legacy keys. */
public final class ConsumerEventKey {
    private ConsumerEventKey() {}

    public static String of(String consumer, String eventId) {
        if (consumer == null || consumer.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("consumer and eventId are required");
        }
        // Length prefix prevents delimiter collisions; 64 hex characters fit the existing Inbox key.
        String value = consumer.length() + ":" + consumer + eventId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
