package com.cloudchunk.api.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Admin-only, read-only operations signals. Endpoints are configured by trusted
 * operators, never supplied by users. No raw labels, annotations or upstream
 * exceptions are included in responses.
 */
@Service
public class OperationsOverviewService {
    private static final int LIMIT = 65536;
    private static final double OBJECTIVE = 0.999; // initial rolling 7-day target
    private static final int EXPECTED_SEVEN_DAY_SAMPLES = 7 * 24 * 60 * 4; // 15s
    private static final Set<String> KNOWN_ALERTS = Set.of(
            "CloudChunkApiScrapeDown", "CloudChunkApiHigh5xx",
            "CloudChunkApiP99LatencyHigh", "CloudChunkHikariConnectionsExhausted");

    private static final String SEVEN_TOTAL =
            "sum(increase(http_server_requests_seconds_count{job=\"cloudchunk-api\"}[7d])) or vector(0)";
    private static final String SEVEN_ERRORS =
            "sum(increase(http_server_requests_seconds_count{job=\"cloudchunk-api\",status=~\"5..\"}[7d])) or vector(0)";
    private static final String HOUR_TOTAL =
            "sum(increase(http_server_requests_seconds_count{job=\"cloudchunk-api\"}[1h])) or vector(0)";
    private static final String HOUR_ERRORS =
            "sum(increase(http_server_requests_seconds_count{job=\"cloudchunk-api\",status=~\"5..\"}[1h])) or vector(0)";
    private static final String SCRAPE_COVERAGE =
            "count_over_time(up{job=\"cloudchunk-api\"}[7d]) or vector(0)";

    public record Slo(String state, double objective, Double observedAvailability,
                      Double burnRate1h, long requests7d, double scrapeCoverage,
                      String window, String note) {}
    public record Alert(String fingerprint, String name, String severity,
                        String state, String startedAt) {}
    public record Overview(String status, Instant sampledAt, Slo slo,
                           List<Alert> alerts, String alertSource) {}

    @FunctionalInterface
    interface Reader {
        JsonNode read(URI uri) throws Exception;
    }

    private final String prometheus;
    private final String alertmanager;
    private final Reader reader;

    @Autowired
    public OperationsOverviewService(ObjectMapper mapper,
            @Value("${cloudchunk.observability.prometheus-query-url:}") String prometheus,
            @Value("${cloudchunk.observability.alertmanager-query-url:}") String alertmanager) {
        this(prometheus, alertmanager, uri -> fetch(mapper, uri));
    }

    OperationsOverviewService(String prometheus, String alertmanager, Reader reader) {
        this.prometheus = safeEndpoint(prometheus, "prometheus");
        this.alertmanager = safeEndpoint(alertmanager, "alertmanager");
        this.reader = reader;
    }

    // Avoid SSRF by accepting only exact in-network names or HTTPS operator endpoint.
    // URLs are configured at deployment, not requested through an API parameter.
    static String safeEndpoint(String input, String service) {
        if (input == null || input.isBlank()) return "";
        try {
            URI uri = URI.create(input);
            if (uri.getUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null || uri.getHost() == null ||
                    (uri.getPath() != null && !uri.getPath().isEmpty() &&
                            !uri.getPath().equals("/"))) return "";
            if ("http".equals(uri.getScheme()) &&
                    uri.getHost().equals(service) &&
                    uri.getPort() == ("prometheus".equals(service) ? 9090 : 9093))
                return input.replaceAll("/+$", "");
            if ("https".equals(uri.getScheme()) && uri.getPort() == -1)
                return input.replaceAll("/+$", "");
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    private static JsonNode fetch(ObjectMapper mapper, URI uri) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("monitor unavailable");
            byte[] bytes = body.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IllegalStateException("monitor response too large");
            return mapper.readTree(bytes);
        }
    }

    public Overview snapshot() {
        Slo slo = noData("UNCONFIGURED", "运维指标未配置");
        List<Alert> alerts = List.of();
        String alertSource = alertmanager.isBlank() ? "UNCONFIGURED" : "UNAVAILABLE";
        if (!prometheus.isBlank()) {
            try {
                double total = scalar(SEVEN_TOTAL);
                double errors = scalar(SEVEN_ERRORS);
                double hour = scalar(HOUR_TOTAL);
                double hourErrors = scalar(HOUR_ERRORS);
                double covered = scalar(SCRAPE_COVERAGE) / EXPECTED_SEVEN_DAY_SAMPLES;
                if (errors > total + 0.001 || hourErrors > hour + 0.001)
                    throw new IllegalStateException("inconsistent counters");
                covered = Math.max(0, Math.min(1, covered));
                double availability = total > 0 ? 1 - errors / total : 0;
                Double burn = hour > 0 ? (hourErrors / hour) / (1 - OBJECTIVE) : null;
                String state = covered < 0.9 ? "WARMING"
                        : total < 100 ? "LOW_TRAFFIC"
                        : availability < OBJECTIVE ? "BREACH" : "OBSERVED";
                slo = new Slo(state, OBJECTIVE, total > 0 ? availability : null, burn,
                        (long)Math.floor(total), covered, "7d",
                        "基于已采集的 HTTP 5xx；不包含未触达应用的请求及外部依赖故障");
            } catch (Exception ignored) {
                slo = noData("UNAVAILABLE", "指标缺失、过期或校验失败");
            }
        }
        if (!alertmanager.isBlank()) {
            try {
                JsonNode root = reader.read(URI.create(alertmanager + "/api/v2/alerts?active=true&silenced=false&inhibited=false"));
                if (!root.isArray() || root.size() > 1000) throw new IllegalStateException("bad alerts");
                List<Alert> result = new ArrayList<>();
                for (JsonNode item : root) {
                    if (result.size() >= 20) break;
                    String name = item.path("labels").path("alertname").asText("");
                    String fingerprint = item.path("fingerprint").asText("");
                    if (!KNOWN_ALERTS.contains(name) ||
                            !fingerprint.matches("[0-9a-fA-F]{16,64}")) continue;
                    String severity = item.path("labels").path("severity").asText("");
                    if (!severity.equals("critical") && !severity.equals("warning")) severity = "unknown";
                    String state = item.path("status").path("state").asText("");
                    if (!state.equals("active") && !state.equals("suppressed")) state = "unknown";
                    String start = item.path("startsAt").asText("");
                    try { OffsetDateTime.parse(start); }
                    catch (Exception ex) { start = ""; }
                    result.add(new Alert(fingerprint.toLowerCase(), name, severity, state, start));
                }
                alerts = List.copyOf(result);
                alertSource = "VERIFIED";
            } catch (Exception ignored) {
                alertSource = "UNAVAILABLE";
            }
        }
        String status = slo.state().equals("UNAVAILABLE") || slo.state().equals("UNCONFIGURED")
                || !alertSource.equals("VERIFIED") ? "PARTIAL" : "READ_ONLY";
        return new Overview(status, Instant.now(), slo, alerts, alertSource);
    }

    private static Slo noData(String state, String note) {
        return new Slo(state, OBJECTIVE, null, null, 0, 0, "7d", note);
    }

    private double scalar(String query) throws Exception {
        String url = prometheus + "/api/v1/query?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode root = reader.read(URI.create(url));
        if (!root.path("status").asText("").equals("success") ||
                !root.path("data").path("resultType").asText("").equals("vector"))
            throw new IllegalStateException("invalid metric response");
        JsonNode result = root.path("data").path("result");
        if (!result.isArray() || result.size() != 1)
            throw new IllegalStateException("no unique sample");
        JsonNode value = result.get(0).path("value");
        if (!value.isArray() || value.size() != 2) throw new IllegalStateException("bad sample");
        double timestamp = value.get(0).asDouble(Double.NaN);
        double number = Double.parseDouble(value.get(1).asText());
        if (!Double.isFinite(number) || number < 0 ||
                !Double.isFinite(timestamp) ||
                Instant.now().getEpochSecond() - timestamp < 0 ||
                Instant.now().getEpochSecond() - timestamp > 120)
            throw new IllegalStateException("stale/nonfinite metric");
        return number;
    }
}
