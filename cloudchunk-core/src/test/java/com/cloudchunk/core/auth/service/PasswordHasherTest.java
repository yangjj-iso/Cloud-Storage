package com.cloudchunk.core.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hash_matchesOriginalPasswordOnly() {
        String encoded = hasher.hash("cloudchunk-password");

        assertThat(encoded).startsWith("$argon2");
        assertThat(hasher.matches("cloudchunk-password", encoded)).isTrue();
        assertThat(hasher.matches("wrong-password", encoded)).isFalse();
    }
}
