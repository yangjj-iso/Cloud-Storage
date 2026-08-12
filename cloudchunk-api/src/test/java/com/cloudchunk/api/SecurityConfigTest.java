package com.cloudchunk.api;

import com.cloudchunk.api.config.SecurityConfig;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.auth.dto.AuthenticatedPrincipal;
import com.cloudchunk.core.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.TestRoutes.class)
@ContextConfiguration(classes = {
        SecurityConfigTest.TestApplication.class,
        SecurityConfig.class,
        SecurityConfigTest.TestRoutes.class
})
class SecurityConfigTest {

    @Autowired private MockMvc mvc;
    @MockBean private AuthService authService;

    @Test
    void pingIsPublicButApplicationRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void validUserCanAccessApplicationRouteButNotAdminRoute() throws Exception {
        when(authService.resolvePrincipal("user-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(7L, "user")));

        mvc.perform(get("/api/v1/private").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/test").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void adminRoleCanAccessAdminRoute() throws Exception {
        when(authService.resolvePrincipal("admin-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(1L, "admin")));

        mvc.perform(get("/api/v1/admin/test").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetricsRequireAdminButHealthRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        when(authService.resolvePrincipal("user-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(7L, "user")));
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());

        when(authService.resolvePrincipal("admin-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(1L, "admin")));
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestRoutes {
        @GetMapping("/api/v1/ping") R<String> ping() { return R.ok("pong"); }
        @GetMapping("/api/v1/private") R<String> privateRoute() { return R.ok("ok"); }
        @GetMapping("/api/v1/admin/test") R<String> adminRoute() { return R.ok("ok"); }
        @GetMapping("/actuator/health") R<String> health() { return R.ok("UP"); }
        @GetMapping("/actuator/metrics") R<String> metrics() { return R.ok("metrics"); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
