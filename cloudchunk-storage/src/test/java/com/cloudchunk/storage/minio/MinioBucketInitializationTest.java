package com.cloudchunk.storage.minio;

import com.cloudchunk.common.exception.StorageException;
import com.cloudchunk.storage.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioBucketInitializationTest {
    private final MinioClient client = mock(MinioClient.class);
    private final StorageProperties properties = new StorageProperties();

    private ErrorResponseException failure(String code) {
        ErrorResponse response = mock(ErrorResponse.class);
        when(response.code()).thenReturn(code);
        ErrorResponseException failure = mock(ErrorResponseException.class);
        when(failure.errorResponse()).thenReturn(response);
        return failure;
    }

    @Test void existingBucketDoesNotWrite() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        new MinioStorageStrategy(client, properties);
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
        verify(client).bucketExists(any(BucketExistsArgs.class));
    }

    @Test void newBucketIsCreatedExactlyOnce() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        new MinioStorageStrategy(client, properties);
        verify(client).makeBucket(any(MakeBucketArgs.class));
        verify(client).bucketExists(any(BucketExistsArgs.class));
    }

    @Test void simultaneousInitializersConvergeWithoutRetryingCreate() throws Exception {
        CyclicBarrier bothSawMissing = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger creates = new AtomicInteger();
        ErrorResponseException owned = failure("BucketAlreadyOwnedByYou");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenAnswer(call -> {
            if (reads.incrementAndGet() <= 2) {
                bothSawMissing.await(3, TimeUnit.SECONDS);
                return false;
            }
            return true;
        });
        doAnswer(call -> {
            if (creates.incrementAndGet() > 1) throw owned;
            return null;
        }).when(client).makeBucket(any(MakeBucketArgs.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new MinioStorageStrategy(client, properties));
            var second = executor.submit(() -> new MinioStorageStrategy(client, properties));
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNotNull();
        }
        verify(client, times(2)).makeBucket(any(MakeBucketArgs.class));
        verify(client, times(3)).bucketExists(any(BucketExistsArgs.class));
    }

    @Test void otherOwnerPermissionAndServerErrorsAreNotSuccess() throws Exception {
        for (String code : new String[]{"BucketAlreadyExists", "AccessDenied", "InternalError", "OperationAborted"}) {
            reset(client);
            ErrorResponseException error = failure(code);
            when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
            doThrow(error).when(client).makeBucket(any(MakeBucketArgs.class));
            assertThatThrownBy(() -> new MinioStorageStrategy(client, properties))
                    .isInstanceOf(StorageException.class).hasCause(error);
            verify(client).bucketExists(any(BucketExistsArgs.class));
            verify(client).makeBucket(any(MakeBucketArgs.class));
        }
    }

    @Test void ownedConflictRequiresSuccessfulReadback() throws Exception {
        ErrorResponseException error = failure("BucketAlreadyOwnedByYou");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        doThrow(error).when(client).makeBucket(any(MakeBucketArgs.class));
        assertThatThrownBy(() -> new MinioStorageStrategy(client, properties))
                .isInstanceOf(StorageException.class).hasCause(error);
        verify(client, times(2)).bucketExists(any(BucketExistsArgs.class));
        verify(client).makeBucket(any(MakeBucketArgs.class));
    }

    @Test void failedReadbackDoesNotRetryOrHideTransportFailure() throws Exception {
        IOException unavailable = new IOException("synthetic readback failure");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false).thenThrow(unavailable);
        doThrow(failure("BucketAlreadyOwnedByYou")).when(client).makeBucket(any(MakeBucketArgs.class));
        assertThatThrownBy(() -> new MinioStorageStrategy(client, properties))
                .isInstanceOf(StorageException.class).hasCause(unavailable);
        verify(client).makeBucket(any(MakeBucketArgs.class));
    }

    @Test void failedInitialReadNeverAttemptsCreate() throws Exception {
        IOException unavailable = new IOException("synthetic initial read failure");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(unavailable);
        assertThatThrownBy(() -> new MinioStorageStrategy(client, properties))
                .isInstanceOf(StorageException.class).hasCause(unavailable);
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test void missingErrorCodeNeverCountsAsAlreadyOwned() throws Exception {
        ErrorResponseException missing = mock(ErrorResponseException.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        doThrow(missing).when(client).makeBucket(any(MakeBucketArgs.class));
        assertThatThrownBy(() -> new MinioStorageStrategy(client, properties))
                .isInstanceOf(StorageException.class).hasCause(missing);
        verify(client).bucketExists(any(BucketExistsArgs.class));
    }
}
