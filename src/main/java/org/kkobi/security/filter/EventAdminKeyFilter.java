package org.kkobi.security.filter;

import org.kkobi.security.util.JsonResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// /api/admin/event/** 운영 API를 X-Admin-Key 헤더로 보호한다.
// EVENT_ADMIN_KEY가 서버에 설정되지 않은 경우 관리자 API를 무인증으로 열어주지 않고 fail-closed로 항상 거절한다.
@Component
public class EventAdminKeyFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PREFIX = "/api/admin/event";
    private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    @Value("${event.admin.key}")
    String adminKey;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith(ADMIN_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (adminKey == null || adminKey.isBlank()) {
            JsonResponse.sendError(response, HttpStatus.UNAUTHORIZED, "관리자 인증이 설정되지 않았습니다.");
            return;
        }

        String requestKey = request.getHeader(ADMIN_KEY_HEADER);
        if (requestKey == null || !MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8),
                requestKey.getBytes(StandardCharsets.UTF_8))) {
            JsonResponse.sendError(response, HttpStatus.UNAUTHORIZED, "유효하지 않은 관리자 정보입니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
