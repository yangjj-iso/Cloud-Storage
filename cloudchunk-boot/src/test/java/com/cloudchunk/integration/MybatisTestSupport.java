package com.cloudchunk.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudchunk.core.config.AuditMetaHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

/**
 * 集成测试用的最小 MyBatis-Plus 装配：直接对着容器里的真实 MySQL 跑生产的 Mapper 与 XML。
 * <p>
 * 刻意不启动 Spring 上下文——这些用例要验证的是 SQL 与事务隔离级别下的行为，
 * 引入完整应用上下文只会把 Redis、RocketMQ、MinIO 一并拖进来。
 */
final class MybatisTestSupport {

    private MybatisTestSupport() {}

    static HikariDataSource migratedDataSource(MySQLContainer<?> mysql, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl());
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setMaximumPoolSize(poolSize);
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return dataSource;
    }

    static SqlSessionFactory sessionFactory(DataSource dataSource, Class<?>... mappers) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        // created_at / updated_at 标了 @TableField(fill = INSERT)，MyBatis-Plus 无论是否为空
        // 都会把它们写进 INSERT 列表。缺了填充器就会插入 NULL 而撞上 NOT NULL 约束，
        // 所以这里复用生产的填充器，保持与运行时一致的写入行为。
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new AuditMetaHandler());
        factory.setGlobalConfig(globalConfig);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        for (Class<?> mapper : mappers) {
            sessionFactory.getConfiguration().addMapper(mapper);
        }
        return sessionFactory;
    }
}
