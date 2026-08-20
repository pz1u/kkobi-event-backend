package org.kkobi.security.config;

import lombok.RequiredArgsConstructor;
import org.kkobi.security.filter.EventAdminKeyFilter;
import org.kkobi.security.util.JsonResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

// 행사 백엔드는 회원 로그인/JWT를 사용하지 않는다.
// 참가자 인증은 EventGameController 등 event 서비스 계층이 X-Participant-Token 값으로 직접 검증하고,
// 관리자 인증은 EventAdminKeyFilter가 X-Admin-Key 헤더를 별도로 검증한다.
// 이 필터체인은 그 두 값을 대신 검증하지 않고, URL 단위 permitAll 여부만 결정한다.
@Configuration
@EnableWebSecurity
@ComponentScan(basePackages = {
        "org.kkobi.security"
})
@RequiredArgsConstructor
public class SecurityConfig {

    private final EventAdminKeyFilter eventAdminKeyFilter;

    @Value("${cors.allowed-origin-patterns:http://localhost:3000,http://localhost:5173}")
    private String allowedOriginPatterns;

    // Spring Security 필터보다 먼저 적용할 UTF-8 인코딩 필터를 생성
    public CharacterEncodingFilter encodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }

    // 예외 처리, URL 인증 규칙, 무상태 세션을 설정
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(encodingFilter(), CsrfFilter.class)
                .addFilterBefore(eventAdminKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                JsonResponse.sendError(response, HttpStatus.UNAUTHORIZED, authException.getMessage()))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                JsonResponse.sendError(response, HttpStatus.FORBIDDEN, "Access denied")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new AntPathRequestMatcher("/**", HttpMethod.OPTIONS.name())).permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher("/"),
                                new AntPathRequestMatcher("/resources/**"),
                                new AntPathRequestMatcher("/assets/**"),
                                new AntPathRequestMatcher("/swagger-ui.html"),
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/v3/api-docs"),
                                new AntPathRequestMatcher("/v3/api-docs/**"),
                                new AntPathRequestMatcher("/error"))
                        .permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher("/api/securities/*/orderable"))
                        .authenticated()
                        .requestMatchers(
                                new AntPathRequestMatcher("/api/games/scenarios/**"),
                                new AntPathRequestMatcher("/api/stocks/**"),
                                new AntPathRequestMatcher("/api/securities/**"),
                                new AntPathRequestMatcher("/api/personas/**"),
                                new AntPathRequestMatcher("/ws-stocks/**"))
                        .permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher(
                                        "/api/products/deposits",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/products/savings",
                                        HttpMethod.GET.name()
                                ),
                                new RegexRequestMatcher(
                                        "^/api/products/(deposits|savings)/\\d+$",
                                        HttpMethod.GET.name()
                                )
                        )
                        .permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/health", HttpMethod.GET.name())).permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher(
                                        "/api/event/participants",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/status",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/me",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/game",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/game",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/game/actions",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/result",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/event/leaderboard",
                                        HttpMethod.GET.name()
                                )
                        )
                        .permitAll()
                        // 행사 관리자 API: Spring Security 인가 단계는 permitAll이지만,
                        // EventAdminKeyFilter(addFilterBefore로 등록됨)가 X-Admin-Key 헤더를 별도로 검증한다.
                        .requestMatchers(
                                new AntPathRequestMatcher(
                                        "/api/admin/event/start",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/admin/event/finish",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/admin/event/participants",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/admin/event/leaderboard",
                                        HttpMethod.GET.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/admin/event/reset",
                                        HttpMethod.POST.name()
                                )
                        )
                        .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    // 브라우저 클라이언트의 API 요청을 허용하는 CORS 필터를 생성
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
