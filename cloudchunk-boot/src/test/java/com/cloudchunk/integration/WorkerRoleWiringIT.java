package com.cloudchunk.integration;

import com.cloudchunk.CloudchunkApplication;
import com.cloudchunk.core.file.task.ObjectGcWorker;
import com.cloudchunk.core.maintenance.RetentionCleanupTask;
import com.cloudchunk.core.upload.task.ChecksumCompensationTask;
import com.cloudchunk.core.upload.task.StaleSessionCleanupTask;
import com.cloudchunk.core.upload.task.TranscodeCompensationTask;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * 以真实的 worker profile 启动，验证这一侧确实把后台组件装配了出来。
 * <p>
 * 只测 api 角色是半个测试：那边断言的是「worker 组件不存在」。如果哪天条件注解被改成永远不生效，
 * api 侧照样通过，而真正的 Worker 会安静地什么都不做——Outbox 不投递、过期会话不清理、
 * 对象不回收，全程没有任何报错。这个方向才是危险的那个。
 * <p>
 * 这里不启动 RocketMQ：消费者容器在启动时就要连 NameServer，为此拉起完整 Broker 会让用例又慢又脆。
 * 消息自动配置被排除，仅以桩替换 {@code RocketMQTemplate}——投递行为不在本用例的验证范围内，
 * 要验证的是除 Broker 客户端之外的整张 bean 依赖图在非 Web 模式下能否装配起来。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CloudchunkApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
            "cloudchunk.storage.type=local"
        })
@ActiveProfiles("worker")
class WorkerRoleWiringIT {

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

    /** Outbox 发布器需要一个真实的 Broker 客户端；这里只关心装配，不关心投递。 */
    @MockBean private RocketMQTemplate rocketMQTemplate;

    @Autowired private ApplicationContext context;

    @Test
    void backgroundComponentsAreWiredWhenTheWorkerRoleIsEnabled() {
        assertThat(context.getBeansOfType(RetentionCleanupTask.class)).hasSize(1);
        assertThat(context.getBeansOfType(StaleSessionCleanupTask.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChecksumCompensationTask.class)).hasSize(1);
        assertThat(context.getBeansOfType(TranscodeCompensationTask.class)).hasSize(1);
        assertThat(context.getBeansOfType(ObjectGcWorker.class)).hasSize(1);
    }

    /** 非 Web 模式下不应该装配 WebSocket 组件，它们属于 API 角色。 */
    @Test
    void webOnlyComponentsStayOutOfTheWorkerRole() {
        assertThat(context.getBeanNamesForType(
                com.cloudchunk.api.ws.UploadProgressBroadcaster.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.cloudchunk.api.ws.UploadProgressHandler.class)).isEmpty();
    }
}
