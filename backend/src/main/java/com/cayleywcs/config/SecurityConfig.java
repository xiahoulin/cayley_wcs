package com.cayleywcs.config;

import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.auth.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private static final String DEFAULT_ALLOWED_ORIGIN_PATTERNS = "http://127.0.0.1:*,http://localhost:*";

    private final List<String> allowedOriginPatterns;

    public SecurityConfig(@Value("${cayleywcs.security.allowed-origin-patterns:" + DEFAULT_ALLOWED_ORIGIN_PATTERNS + "}") String allowedOriginPatterns) {
        this.allowedOriginPatterns = parseAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                            AppKeyAuthFilter appKeyAuthFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authEx) -> {
                    // 未认证/令牌过期返回标准 401（带 ApiResponse JSON），而非默认 403
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                            "{\"isSuccess\":false,\"code\":401,\"errorMessage\":\"未认证或令牌已过期\",\"data\":null}");
                }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/health",
                                "/login",
                                "/refresh-token",
                                // 开放接口走独立 AppKey 鉴权（AppKeyAuthFilter），此处对 Spring Security 放行
                                "/open/**",
                                "/ws/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(appKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(List.of("POST", "GET", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static List<String> parseAllowedOriginPatterns(String value) {
        List<String> patterns = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isBlank())
                .toList();
        if (patterns.isEmpty()) {
            return Arrays.stream(DEFAULT_ALLOWED_ORIGIN_PATTERNS.split(",")).toList();
        }
        return patterns;
    }
}
