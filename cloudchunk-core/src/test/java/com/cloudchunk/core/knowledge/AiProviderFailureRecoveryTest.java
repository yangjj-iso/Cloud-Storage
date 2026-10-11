package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Disposable loopback-only provider: no real provider calls or model charges. */
class AiProviderFailureRecoveryTest {
    @Test
    void chatProviderUnavailableNeverLeaksProviderResponseAndRecovers() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        HttpServer fake = start(unavailable, """
            {"choices":[{"message":{"content":"synthetic response"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":2}}
            """);
        try {
            var environment = env(Map.of(
                "cloudchunk.ai.chat.endpoint", "http://127.0.0.1:" + fake.getAddress().getPort() + "/chat",
                "cloudchunk.ai.chat.model", "mock-model",
                "cloudchunk.ai.chat.allow-http", "true",
                "cloudchunk.ai.chat.api-key", "synthetic-provider-secret"));
            var gateway = new OpenAiCompatibleChatGateway(new ObjectMapper(), environment);
            assertThat(gateway.configured()).isTrue();
            assertThatThrownBy(() -> gateway.complete("question", java.util.List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException)e).getErrorCode())
                    .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE))
                .hasMessageNotContaining("synthetic-provider-secret", "upstream-secret");
            unavailable.set(false);
            assertThat(gateway.complete("question", java.util.List.of())).isEqualTo("synthetic response");
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void embeddingProviderOutageThenRecoveryRestoresFiniteVectors() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        HttpServer fake = start(unavailable, """
            {"data":[{"embedding":[1,0,0,0,0,0,0,0]}],
             "usage":{"total_tokens":1}}
            """);
        try {
            var environment = env(Map.of(
                "cloudchunk.ai.embedding.endpoint", "http://127.0.0.1:" + fake.getAddress().getPort() + "/embeddings",
                "cloudchunk.ai.embedding.model", "test-model",
                "cloudchunk.ai.embedding.enabled", "true",
                "cloudchunk.ai.embedding.allow-http", "true",
                "cloudchunk.ai.embedding.dimensions", "8"));
            var gateway = new OpenAiCompatibleEmbeddingGateway(new ObjectMapper(), environment);
            assertThatThrownBy(() -> gateway.embed("query"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException)e).getErrorCode())
                    .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
            unavailable.set(false);
            assertThat(gateway.embed("query")).hasSize(8).containsExactly(1.,0.,0.,0.,0.,0.,0.,0.);
        } finally {
            fake.stop(0);
        }
    }

    private static StandardEnvironment env(Map<String, Object> values) {
        StandardEnvironment result = new StandardEnvironment();
        result.getPropertySources().addFirst(new MapPropertySource("synthetic-ci", values));
        return result;
    }

    private static HttpServer start(AtomicBoolean unavailable, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = (unavailable.get() ? "upstream-secret: simulated error" : response)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
