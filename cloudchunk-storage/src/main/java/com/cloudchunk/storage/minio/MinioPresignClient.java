package com.cloudchunk.storage.minio;

import com.cloudchunk.storage.StorageProperties;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;

import java.net.URI;

/** Signing-only client. Data, checksums, bucket bootstrap and composition stay internal. */
public final class MinioPresignClient implements AutoCloseable {
    private final MinioClient client;
    private final boolean owned;

    private MinioPresignClient(MinioClient client, boolean owned) {
        this.client = client;
        this.owned = owned;
    }

    public static MinioPresignClient create(StorageProperties props, MinioClient internal,
                                            OkHttpClient http) {
        var m = props.getMinio();
        String endpoint = m.getPublicEndpoint();
        if (endpoint == null || endpoint.isEmpty()) return new MinioPresignClient(internal, false);
        validate(endpoint, m.isPublicEndpointAllowHttp());
        // An explicit region makes signing local: no discovery request to the public gateway.
        if (m.getRegion() == null || m.getRegion().isBlank())
            throw new IllegalArgumentException("Public MinIO signing requires an explicit region");
        var signer = MinioClient.builder().endpoint(endpoint)
                .credentials(m.getAccessKey(), m.getSecretKey()).region(m.getRegion())
                .httpClient(http).build();
        return new MinioPresignClient(signer, true);
    }

    static void validate(String endpoint, boolean allowHttp) {
        try {
            URI uri = URI.create(endpoint);
            String path = uri.getRawPath();
            if ((!"https".equals(uri.getScheme()) && !(allowHttp && "http".equals(uri.getScheme())))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path))
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")
                    || !endpoint.equals(endpoint.strip()) || endpoint.indexOf('\\') >= 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            // Never echo a misconfigured URL which might contain credentials or query secrets.
            throw new IllegalArgumentException("Public MinIO endpoint must be an HTTPS origin; HTTP needs explicit isolated-development opt-in");
        }
    }

    public MinioClient client() { return client; }

    @Override
    public void close() throws Exception {
        if (owned) client.close();
    }
}
