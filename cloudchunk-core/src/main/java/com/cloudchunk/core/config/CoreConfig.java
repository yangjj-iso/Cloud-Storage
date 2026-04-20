package com.cloudchunk.core.config;

import com.cloudchunk.common.concurrent.BoundedVirtualThreadExecutor;
import com.cloudchunk.core.CloudchunkProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CloudchunkProperties.class)
public class CoreConfig {

    /**
     * 通用 IO 后台执行器：MinIO 删除、MQ 补偿、异步小任务。
     * 虚拟线程承载并发，信号量设下游并发上限防止资源压垮。
     */
    @Bean(destroyMethod = "close")
    public BoundedVirtualThreadExecutor ioExecutor(CloudchunkProperties props) {
        CloudchunkProperties.Executor e = props.getExecutor();
        return new BoundedVirtualThreadExecutor("io", e.getIoConcurrency(), e.getAcquireTimeoutMs());
    }

    /** 粗粒度清理任务执行器：合并后分片删除、孤儿对象扫描等。 */
    @Bean(destroyMethod = "close")
    public BoundedVirtualThreadExecutor cleanupExecutor(CloudchunkProperties props) {
        CloudchunkProperties.Executor e = props.getExecutor();
        return new BoundedVirtualThreadExecutor("cleanup", e.getCleanupConcurrency(), e.getAcquireTimeoutMs());
    }
}
