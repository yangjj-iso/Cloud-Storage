package com.cloudchunk.integration;

import com.cloudchunk.CloudchunkApplication;
import com.cloudchunk.core.knowledge.KnowledgeIndexStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real servlet/security/service/database journey. Docker absence is a failure, not a skip.
 * Files and extracted text are synthetic fixtures: this does NOT test upload workers or a model provider.
 */
@Testcontainers
@SpringBootTest(classes = CloudchunkApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cloudchunk.storage.type=local")
@ActiveProfiles("api")
class ProductJourneyIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk").withUsername("cloudchunk").withPassword("cloudchunk");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void containers(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeIndexStore index;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String PASSWORD = "Synthetic-only-pass-46!";
    private static final String K = "/api/v1/knowledge";
    record Account(long id, String username, String token) {}
    record Fixture(Account owner, Account other, long file, long second, long foreign, long folder) {}

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method, String path, Object body, String token) throws Exception {
        var response = call(method, path, body, token);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP failure at " + path);
        var result = json.readTree(response.body());
        assertEquals(0, result.path("code").asInt(-1), "Application failure at " + path);
        return result.path("data");
    }
    private void denied(String method, String path, Object body, String token) throws Exception {
        var response = call(method, path, body, token);
        assertTrue(response.statusCode() >= 400 || json.readTree(response.body()).path("code").asInt(-1) != 0,
                "Unauthorized action must fail: " + path);
    }
    private Account account() throws Exception {
        String username = "m46_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var auth = ok("POST", "/api/v1/auth/register", Map.of("username", username, "password", PASSWORD), null);
        long id = auth.path("user").path("id").asLong();
        assertTrue(id > 0); assertFalse(auth.path("token").asText().isBlank());
        return new Account(id, username, auth.path("token").asText());
    }
    private long entry(long owner, String fileId, String name, boolean directory) {
        jdbc.update("INSERT INTO user_file(user_id,file_id,file_name,is_dir,parent_id) VALUES (?,?,?,?,0)",
                owner, fileId, name, directory ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM user_file WHERE user_id=? AND file_name=? AND parent_id=0",
                Long.class, owner, name);
    }
    private Fixture fixture() throws Exception {
        Account owner = account(), other = account();
        String fileId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO file_meta(file_id,file_md5,file_name,file_size,mime_type,bucket,object_key,owner_id,status)
                VALUES (?,?,'Acceptance.txt',64,'text/plain','synthetic','m46-fixture',?,2)
                """, fileId, fileId, owner.id());
        long file = entry(owner.id(), fileId, "Acceptance.txt", false);
        long second = entry(owner.id(), fileId, "Keep.txt", false);
        long foreign = entry(other.id(), fileId, "Other-private.txt", false);
        long folder = entry(owner.id(), null, "Acceptance archive", true);
        var tx = new TransactionTemplate(transactions);
        var claim = tx.execute(status -> index.claim(fileId));
        assertNotNull(claim);
        tx.executeWithoutResult(status -> index.publish(claim,
                "M46 synthetic acceptance evidence for authorization and reviewed organization.", "READY", null));
        return new Fixture(owner, other, file, second, foreign, folder);
    }
    private long parent(long id) {
        return jdbc.queryForObject("SELECT parent_id FROM user_file WHERE id=?", Long.class, id);
    }

    @Test void realHttpJourneyPreservesOwnershipAndSeparatesApprovalFromExecution() throws Exception {
        var f = fixture(); String token = f.owner().token();
        denied("POST", K + "/search", Map.of("query", "M46"), null);
        assertEquals(f.owner().id(), ok("GET", "/api/v1/auth/me", null, token).path("id").asLong());
        var result = ok("POST", K + "/search", Map.of("query", "M46"), token);
        assertFalse(result.path("evidence").isEmpty());
        for (var hit : result.path("evidence")) assertNotEquals(String.valueOf(f.foreign()), hit.path("userFileId").asText());
        var template = ok("POST", K + "/organize/templates", Map.of("name", "HTTP acceptance", "mimeCategory", "TEXT",
                "destinationId", String.valueOf(f.folder())), token);
        String templateId = template.path("id").asText();
        assertTrue(ok("GET", K + "/organize/templates", null, f.other().token()).isEmpty());
        denied("POST", K + "/organize/simulate", Map.of("templateId", templateId,
                "userFileIds", List.of(String.valueOf(f.foreign()))), token);
        var plan = ok("POST", K + "/organize/simulate", Map.of("templateId", templateId,
                "userFileIds", List.of(String.valueOf(f.file()), String.valueOf(f.second()))), token);
        String path = K + "/organize/runs/" + plan.path("id").asText();
        assertEquals(0, parent(f.file())); assertEquals(0, parent(f.second()));
        denied("GET", path, null, f.other().token());
        denied("POST", path + "/steps/1/execute", Map.of("confirmed", true), token);
        ok("POST", path + "/steps/1/approve", Map.of("confirmed", true), token);
        assertEquals(0, parent(f.file()), "Approval must not move the file");
        denied("POST", path + "/steps/1/execute", Map.of("confirmed", true), f.other().token());
        ok("POST", path + "/steps/1/execute", Map.of("confirmed", true), token);
        assertEquals(f.folder(), parent(f.file()));
        assertEquals(0, parent(f.second()), "No automatic execution of the next step");
        assertEquals(0, parent(f.foreign()));
        ok("POST", path + "/cancel", Map.of("confirmed", true), token);
        assertEquals(f.folder(), parent(f.file()), "Cancellation is not rollback");
        assertEquals(0, parent(f.second()));
        assertEquals("CANCELLED", ok("GET", path, null, token).path("status").asText());
    }

    @Test void realBrowserJourneyWhenAcceptanceRunnerIsExplicitlyEnabled() throws Exception {
        // Normal backend verification has no Python/browser dependency. The dedicated gate
        // sets this property and REQUIRES a successful browser report from this invocation.
        if (!Boolean.getBoolean("cloudchunk.browser")) return;
        var f = fixture();
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".." )).toAbsolutePath();
        Path fixtureFile = Files.createTempFile("cloudchunk-m46-synthetic-", ".json");
        Process process = null;
        try {
            json.writeValue(fixtureFile.toFile(), Map.of("username", f.owner().username(), "password", PASSWORD,
                    "fileId", String.valueOf(f.file()), "folderId", String.valueOf(f.folder())));
            process = new ProcessBuilder("python3", root.resolve("deploy/acceptance/browser_live.py").toString(),
                    "--api", "http://127.0.0.1:" + port, "--dist", root.resolve("cloudchunk-web/dist").toString(),
                    "--fixture", fixtureFile.toString(), "--report", root.resolve("artifacts/m46/fullstack.json").toString())
                    .directory(root.toFile()).inheritIO().start();
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Browser journey exceeded its bounded timeout");
            assertEquals(0, process.exitValue(), "Real-browser acceptance failed");
            assertEquals(f.folder(), parent(f.file()));
            assertEquals(0, parent(f.second()));
            assertEquals(0, parent(f.foreign()));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(fixtureFile);
        }
    }
}
