package com.cloudchunk.api.config;

import com.cloudchunk.api.filter.AuthenticationContextFilter;
import com.cloudchunk.api.filter.TraceFilter;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.core.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.util.regex.Pattern;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final Pattern PUBLIC_SHARE = Pattern.compile(
            "^/api/v1/share/[0-9a-fA-F]{16,32}(?:/(?:children|download|download-zip))?$");

    @Bean
    public TraceFilter traceFilter(ObjectProvider<Tracer> tracerProvider) {
        return new TraceFilter(tracerProvider);
    }

    @Bean
    public AuthenticationContextFilter authenticationContextFilter(AuthService authService) {
        return new AuthenticationContextFilter(authService);
    }

    /** The filter is owned by Spring Security and must not be registered a second time by the servlet container. */
    @Bean
    public FilterRegistrationBean<TraceFilter> disableTraceFilterAutoRegistration(TraceFilter filter) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AuthenticationContextFilter> disableAuthenticationFilterAutoRegistration(
            AuthenticationContextFilter filter) {
        FilterRegistrationBean<AuthenticationContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TraceFilter traceFilter,
                                                   AuthenticationContextFilter authenticationContextFilter,
                                                   @Value("${cloudchunk.observability.prometheus-public:false}")
                                                   boolean prometheusPublic,
                                                   ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/ping", "/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/auth/send-code", "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(request -> HttpMethod.GET.matches(request.getMethod())
                                && PUBLIC_SHARE.matcher(request.getRequestURI()).matches()).permitAll()
                        .requestMatchers("/ws/upload/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(request -> prometheusPublic
                                && HttpMethod.GET.matches(request.getMethod())
                                && "/actuator/prometheus".equals(request.getRequestURI())).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            ErrorCode code = ErrorCode.UNAUTHORIZED;
                            response.setStatus(code.getHttpStatus());
                            response.setContentType("application/json;charset=UTF-8");
                            R<Void> body = R.fail(code);
                            body.setTraceId(TraceContext.get());
                            objectMapper.writeValue(response.getWriter(), body);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            ErrorCode code = ErrorCode.FORBIDDEN;
                            response.setStatus(code.getHttpStatus());
                            response.setContentType("application/json;charset=UTF-8");
                            R<Void> body = R.fail(code);
                            body.setTraceId(TraceContext.get());
                            objectMapper.writeValue(response.getWriter(), body);
                        }))
                .addFilterAfter(traceFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(authenticationContextFilter, TraceFilter.class);
        return http.build();
    }
}
