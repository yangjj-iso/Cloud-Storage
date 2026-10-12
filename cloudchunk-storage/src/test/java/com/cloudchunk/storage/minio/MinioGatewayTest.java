package com.cloudchunk.storage.minio;

import com.cloudchunk.common.exception.StorageException;
import com.cloudchunk.storage.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioGatewayTest {
    @Test void signsPublicUrlsLocallyWithoutMovingDataOrBucketRequestsToTheGateway() throws Exception {
        var props = new StorageProperties();
        props.getMinio().setPublicEndpoint("https://objects.example.test:9443");
        var internal = mock(MinioClient.class);
        when(internal.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        var requests = new AtomicInteger();
        var http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Signing must not contact a gateway");
        }).build();
        try (var signer = MinioPresignClient.create(props, internal, http)) {
            var storage = new MinioStorageStrategy(internal, props, signer.client());
            for (String url : new String[]{storage.presignUpload("cloudchunk", "folder/space name.txt", Duration.ofMinutes(5)),
                    storage.presignDownload("cloudchunk", "folder/space name.txt", Duration.ofMinutes(5))}) {
                URI uri = URI.create(url);
                assertThat(uri.getScheme()).isEqualTo("https");
                assertThat(uri.getHost()).isEqualTo("objects.example.test");
                assertThat(uri.getPort()).isEqualTo(9443);
                assertThat(uri.getRawPath()).isEqualTo("/cloudchunk/folder/space%20name.txt");
                assertThat(uri.getRawQuery()).contains("X-Amz-Signature=", "X-Amz-SignedHeaders=host");
            }
            assertThat(requests.get()).isZero();
            verify(internal).bucketExists(any(BucketExistsArgs.class));
            verifyNoMoreInteractions(internal);
        }
    }

    @Test void blankPublicEndpointPreservesInternalClientWithoutDoubleClose() throws Exception {
        var internal = mock(MinioClient.class);
        try (var signer = MinioPresignClient.create(new StorageProperties(), internal, new OkHttpClient())) {
            assertThat(signer.client()).isSameAs(internal);
        }
        verifyNoInteractions(internal);
    }

    @Test void rejectsUnsafePublicOriginsWithoutEchoingConfigurationSecrets() {
        for (String value : new String[]{"http://objects.test", "https://u:secret@objects.test", "https://objects.test/path",
                "https://objects.test?token=secret", "https://objects.test#secret", "https://objects.test:0",
                "https://objects.test:99999", "https://objects.test:", " https://objects.test", "https://*.test",
                "https://objects.test\\evil", "//objects.test", "https://objects.test/%2e%2e"}) {
            assertThatThrownBy(() -> MinioPresignClient.validate(value, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
    }

    @Test void httpRequiresExplicitDevelopmentOptInAndNeverAllowsUrlCredentials() {
        assertThatCode(() -> MinioPresignClient.validate("http://gateway:8081", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MinioPresignClient.validate("http://u:p@gateway:8081", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> MinioPresignClient.validate("https://objects.test/", false)).doesNotThrowAnyException();
    }

    @Test void publicSigningRequiresExplicitRegionBeforeAnyNetworkRequest() {
        var props = new StorageProperties();
        props.getMinio().setPublicEndpoint("https://objects.test");
        props.getMinio().setRegion("");
        var internal = mock(MinioClient.class);
        assertThatThrownBy(() -> MinioPresignClient.create(props, internal, new OkHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(internal);
    }

    @Test void bootstrapAcceptsOnlyConfirmedSameOwnerRaceWithoutRetryingCreate() throws Exception {
        var client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false, true);
        doThrow(error("BucketAlreadyOwnedByYou")).when(client).makeBucket(any(MakeBucketArgs.class));
        assertThatCode(() -> new MinioStorageStrategy(client, new StorageProperties())).doesNotThrowAnyException();
        verify(client, times(2)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(1)).makeBucket(any(MakeBucketArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test void sameOwnerRaceStillFailsWhenTheReadbackIsMissingOrDenied() throws Exception {
        for (boolean denied : new boolean[]{false, true}) {
            var client = mock(MinioClient.class);
            if (denied) when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false).thenThrow(error("AccessDenied"));
            else when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false, false);
            doThrow(error("BucketAlreadyOwnedByYou")).when(client).makeBucket(any(MakeBucketArgs.class));
            assertThatThrownBy(() -> new MinioStorageStrategy(client, new StorageProperties())).isInstanceOf(StorageException.class);
            verify(client, times(1)).makeBucket(any(MakeBucketArgs.class));
        }
    }

    @Test void neverSwallowsForeignOwnershipPermissionsOrTransportFailures() throws Exception {
        for (Exception failure : new Exception[]{error("BucketAlreadyExists"), error("AccessDenied"), new IOException("offline")}) {
            var client = mock(MinioClient.class);
            when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
            doThrow(failure).when(client).makeBucket(any(MakeBucketArgs.class));
            assertThatThrownBy(() -> new MinioStorageStrategy(client, new StorageProperties())).isInstanceOf(StorageException.class);
            verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
            verify(client, times(1)).makeBucket(any(MakeBucketArgs.class));
        }
    }

    private ErrorResponseException error(String code) {
        var error = mock(ErrorResponseException.class);
        var body = mock(ErrorResponse.class);
        when(body.code()).thenReturn(code);
        when(error.errorResponse()).thenReturn(body);
        return error;
    }
}
