package com.cloudchunk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 只有 servlet 环境才装配交付层。
 * <p>
 * Controller、过滤器、WebSocket 和 SecurityConfig 都依赖 Web 上下文才存在的东西。放任它们被
 * Worker 扫到，启动就会直接失败在类似「UploadController 找不到 UploadProgressBroadcaster」
 * 这样的依赖上——而这个 bean 本身正确地标注了只在 Web 环境下存在。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ComponentScan("com.cloudchunk.api")
public class ApiRoleConfiguration {
}
