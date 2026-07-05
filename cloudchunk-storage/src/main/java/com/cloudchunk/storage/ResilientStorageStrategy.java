package com.cloudchunk.storage;

import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.GetRangeRequest;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.ObjectStat;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import com.cloudchunk.storage.model.RangeStream;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 为底层 {@link StorageStrategy} 增加熔断保护的装饰器。
 *
 * <p>当对象存储（MinIO）持续故障（异常率超阈值）时，熔断器打开，后续调用<strong>快速失败</strong>，
 * 避免请求线程全部堆积在超时的存储调用上导致级联雪崩；冷却后半开试探，恢复即自动关闭。</p>
 *
 * <p>只包裹熔断、<strong>不做重试</strong>：{@link #put} / {@link #get} 涉及不可重放的输入/输出流，
 * 盲目重试会读到已消费的流。MQ 侧（可重放消息）才叠加重试。</p>
 */
public class ResilientStorageStrategy implements StorageStrategy {

    private final StorageStrategy delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientStorageStrategy(StorageStrategy delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    private <T> T call(Supplier<T> supplier) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, supplier).get();
    }

    private void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public PutResult put(PutRequest request) {
        return call(() -> delegate.put(request));
    }

    @Override
    public InputStream get(GetRequest request) {
        return call(() -> delegate.get(request));
    }

    @Override
    public RangeStream getRange(GetRangeRequest request) {
        return call(() -> delegate.getRange(request));
    }

    @Override
    public ComposeResult compose(ComposeRequest request) {
        return call(() -> delegate.compose(request));
    }

    @Override
    public String presignDownload(String bucket, String objectKey, Duration ttl) {
        return call(() -> delegate.presignDownload(bucket, objectKey, ttl));
    }

    @Override
    public String presignUpload(String bucket, String objectKey, Duration ttl) {
        return call(() -> delegate.presignUpload(bucket, objectKey, ttl));
    }

    @Override
    public void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        run(() -> delegate.copy(srcBucket, srcKey, dstBucket, dstKey));
    }

    @Override
    public void delete(String bucket, String objectKey) {
        run(() -> delegate.delete(bucket, objectKey));
    }

    @Override
    public void deleteBatch(String bucket, List<String> keys) {
        run(() -> delegate.deleteBatch(bucket, keys));
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        return call(() -> delegate.exists(bucket, objectKey));
    }

    @Override
    public ObjectStat stat(String bucket, String objectKey) {
        return call(() -> delegate.stat(bucket, objectKey));
    }

    @Override
    public List<ObjectStat> list(String bucket, String prefix, int maxKeys) {
        return call(() -> delegate.list(bucket, prefix, maxKeys));
    }
}
