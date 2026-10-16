package com.dating.safety.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.safety.application.BehaviorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Marks the authenticated account as active today (behavioral metric "active days"). Never fails a request.
 */
@Configuration
public class ActivityTracking implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ActivityTracking.class);

    private final BehaviorService behaviorService;

    public ActivityTracking(BehaviorService behaviorService) {
        this.behaviorService = behaviorService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
                    try {
                        behaviorService.touch(user.accountId());
                    } catch (RuntimeException e) {
                        log.warn("Activity day was not recorded: {}", e.getMessage());
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
