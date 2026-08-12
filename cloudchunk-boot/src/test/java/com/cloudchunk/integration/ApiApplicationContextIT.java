package com.cloudchunk.integration;

import com.cloudchunk.CloudchunkApplication;
import com.cloudchunk.core.maintenance.RetentionCleanupTask;
import com.cloudchunk.infra.config.RedisWorkloadConfiguration;
import com.cloudchunk.infra.redis.RedisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以 api 运行角色真实启动一次完整应用上下文。
 * <p>
 * 在这个用例出现之前，项目里没有任何测试加载完整上下文——所有 Spring 测试都是切片的。
 * 也就是说注入歧义、循环依赖、配置绑定失败、限定符写错这一整类「应用起不来」的问题，
 * 只有在真正部署时才会暴露，而这个项目近期恰好大改过模块划分、安全配置和 Redis 装配。
 * <p>
 * 顺带验证 api / worker 的角色拆分确实生效：这条边界此前同样没有任何测试覆盖，
 * 一旦条件注解写错，API 节点会跟着跑定时任务和消息消费者。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CloudchunkApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
class ApiApplicationContextIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk")
            .withUsername("cloudchunk")
            .withPassword("cloudchunk");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void pointAtContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private ApplicationContext context;

    @Test
    void theApiRoleStartsWithMigrationsAppliedAndWebEndpointsWired() {
        // Flyway 在启动时跑完，SettingsInitializer 也在启动时读了一次系统设置——
        // 后者正是「AdminService 的管理员校验不能加在启动期内部调用上」那条约束的活证明。
        assertThat(context.containsBean("flyway")).isTrue();

        // WebSocket 扇出的三个组件都带 @ConditionalOnWebApplication(SERVLET)，
        // 只有真正的 servlet 环境才会装配。
        assertThat(context.getBeansOfType(
                com.cloudchunk.api.ws.UploadProgressBroadcaster.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                com.cloudchunk.api.ws.UploadProgressHandler.class)).hasSize(1);
    }

    /** 拆分后容器里有四个 RedisService，未加限定符的注入必须唯一落到会话实例。 */
    @Test
    void redisWorkloadsResolveWithoutAmbiguity() {
        assertThat(context.getBeansOfType(RedisService.class)).hasSize(4);
        assertThat(context.getBean(RedisService.class))
                .isSameAs(context.getBean("redisService", RedisService.class));
        assertThat(context.getBean(RedisWorkloadConfiguration.PROGRESS, RedisService.class)).isNotNull();
        assertThat(context.getBean(RedisWorkloadConfiguration.RATE_LIMIT, RedisService.class)).isNotNull();
        assertThat(context.getBean(RedisWorkloadConfiguration.CACHE, RedisService.class)).isNotNull();
    }

    /**
     * api 角色不能承担后台工作。这些 bean 一旦漏进 API 节点，定时任务会随着 API 扩容成倍执行，
     * 而 API 节点本来就不该消费消息。
     */
    @Test
    void workerOnlyComponentsAreAbsentFromTheApiRole() {
        assertThat(context.getBeansOfType(RetentionCleanupTask.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.cloudchunk.core.upload.task.StaleSessionCleanupTask.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.cloudchunk.core.upload.task.ChecksumCompensationTask.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.cloudchunk.core.file.task.ObjectGcWorker.class)).isEmpty();
    }

    /** api profile 排除了 RocketMQ 自动配置，模板不该存在。 */
    @Test
    void theApiRoleDoesNotWireAMessageProducer() {
        assertThat(context.getBeanNamesForType(
                org.apache.rocketmq.spring.core.RocketMQTemplate.class)).isEmpty();
    }
}
