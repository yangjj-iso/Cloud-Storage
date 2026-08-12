package com.cloudchunk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 同一个制品的两种运行角色由组件扫描边界区分。
 * <p>
 * 这里刻意不扫描 {@code com.cloudchunk.api}，交付层改由 {@link ApiRoleConfiguration} 在
 * servlet 环境下引入。Worker 以 {@code web-application-type: none} 运行，扫到 Controller、
 * 过滤器和 SecurityConfig 只会得到一堆无法满足的依赖——它们要的是 Web 上下文才有的东西。
 * <p>
 * 注意 MyBatis 的 Mapper 扫描不受这里影响：它依据的是 {@code @AutoConfigurationPackage}，
 * 也就是本类所在的 {@code com.cloudchunk}。
 */
@SpringBootApplication(scanBasePackages = {
        "com.cloudchunk.core",
        "com.cloudchunk.infra",
        "com.cloudchunk.storage",
        "com.cloudchunk.mq",
        "com.cloudchunk.transcode"
})
@Import({ObservabilityConfiguration.class, ApiRoleConfiguration.class})
@EnableScheduling
public class CloudchunkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudchunkApplication.class, args);
    }
}
