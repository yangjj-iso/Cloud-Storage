package com.cloudchunk.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class StagingAcceptanceControllerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StagingAcceptanceController.class);
    private ByteArrayResource revision(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Test void absentWithoutProfileEvenWhenEnabled() {
        runner.withPropertyValues("cloudchunk.acceptance.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(StagingAcceptanceController.class));
    }
    @Test void absentWithoutExplicitEnable() {
        runner.withPropertyValues("spring.profiles.active=acceptance")
                .run(c -> assertThat(c).doesNotHaveBean(StagingAcceptanceController.class));
    }
    @Test void productionProfilesSuppressEndpointEvenIfEnabled() {
        for (String profile : new String[]{"prod", "production"}) {
            runner.withPropertyValues("spring.profiles.active=acceptance," + profile,
                    "cloudchunk.acceptance.enabled=true")
                    .run(c -> assertThat(c).doesNotHaveBean(StagingAcceptanceController.class));
        }
    }
    @Test void acceptsStampedStagingIdentityButStillRequiresAuthentication() throws Exception {
        var controller = new StagingAcceptanceController("m47-test", "staging", "minio", revision("a".repeat(40)));
        assertThatThrownBy(controller::identity).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsInvalidIdentityAndUnstampedArtifact() {
        for (String sha : new String[]{"", "main", "a".repeat(39), "g".repeat(40), "a".repeat(43)}) {
            assertThatThrownBy(() -> new StagingAcceptanceController("m47-test", "staging", "minio", revision(sha)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new StagingAcceptanceController("prod", "staging", "minio", revision("a".repeat(40))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StagingAcceptanceController("m47-test", "production", "minio", revision("a".repeat(40))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StagingAcceptanceController("m47-test", "staging", "local", revision("a".repeat(40))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StagingAcceptanceController("m47-test", "staging", "minio",
                new ClassPathResource("META-INF/nonexistent-m47-revision.txt"))).isInstanceOf(java.io.IOException.class);
    }
}
