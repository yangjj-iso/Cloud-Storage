package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Read-only identity. No setup, fixture insertion or bypass of normal authentication. */
@RestController
@RequestMapping("/api/v1/acceptance")
@Profile("acceptance & !prod & !production")
@ConditionalOnProperty(name = "cloudchunk.acceptance.enabled", havingValue = "true")
public class StagingAcceptanceController {
    public record Identity(String environment, String revision, String mode, String storage) {}
    private final Identity identity;

    public StagingAcceptanceController(
            @Value("${cloudchunk.acceptance.environment:}") String environment,
            @Value("${cloudchunk.acceptance.mode:}") String mode,
            @Value("${cloudchunk.storage.type:}") String storage,
            @Value("classpath:META-INF/cloudchunk-revision.txt") Resource revisionResource) throws IOException {
        String revision;
        try (var stream = revisionResource.getInputStream()) {
            revision = new String(stream.readNBytes(42), StandardCharsets.US_ASCII).strip();
        }
        if (!environment.matches("m47-[a-z0-9-]{1,48}") || !revision.matches("[0-9a-f]{40}")
                || !Set.of("isolated", "staging").contains(mode) || !"minio".equals(storage)) {
            throw new IllegalArgumentException("Invalid isolated/staging acceptance identity");
        }
        // The revision comes from the packaged artifact, not a mutable environment variable.
        identity = new Identity(environment, revision, mode, storage);
    }

    @GetMapping("/identity")
    public ResponseEntity<R<Identity>> identity() {
        UserContext.requireUserId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(identity));
    }
}
