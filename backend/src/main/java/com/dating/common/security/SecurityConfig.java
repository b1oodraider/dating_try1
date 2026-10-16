package com.dating.common.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final JsonSecurityErrorHandler securityErrorHandler;
    private final CorsProperties corsProperties;

    public SecurityConfig(SessionAuthenticationFilter sessionAuthenticationFilter,
                          JsonSecurityErrorHandler securityErrorHandler,
                          CorsProperties corsProperties) {
        this.sessionAuthenticationFilter = sessionAuthenticationFilter;
        this.securityErrorHandler = securityErrorHandler;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags", "/api/v1/goals").permitAll()
                        // Signed URLs: authorization is the HMAC signature, so <img src> works without a header.
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/photos/*").permitAll()
                        // Cosmetics are public, so are their images (immutable by id).
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/images/*").permitAll()
                        // Staff endpoints: authentication here, roles are checked per action (RoleAccess, from the DB).
                        .requestMatchers("/api/v1/admin/**").authenticated()
                        // SSE stream: authenticated in EventsController (Authorization header or a one-time ticket,
                        // EventSource cannot send headers); permitting it here also covers the async dispatches.
                        .requestMatchers(HttpMethod.GET, "/api/v1/events").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The filter is a @Component only so it can be injected; it must run inside the security chain, not twice.
     */
    @Bean
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionFilterRegistration(SessionAuthenticationFilter filter) {
        FilterRegistrationBean<SessionAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
        // Bearer tokens, no cookies: credentials are not needed and must not be combined with broad origins.
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
