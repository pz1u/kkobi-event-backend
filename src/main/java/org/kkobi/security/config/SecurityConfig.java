package org.kkobi.security.config;

import lombok.RequiredArgsConstructor;
import org.kkobi.security.filter.JwtUsernamePasswordAuthenticationFilter;
import org.kkobi.security.jwt.JwtAuthenticationFilter;
import org.kkobi.security.token.RefreshTokenCookieManager;
import org.kkobi.security.token.RefreshTokenService;
import org.kkobi.security.util.JsonResponse;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@Configuration
@EnableWebSecurity
@MapperScan(basePackages = {
        "org.kkobi.users.mapper"
})
@ComponentScan(basePackages = {
        "org.kkobi.security",
        "org.kkobi.users.service"
})
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieManager refreshTokenCookieManager;

    @Value("${cors.allowed-origin-patterns:http://localhost:3000,http://localhost:5173}")
    private String allowedOriginPatterns;

    // Spring Security 필터보다 먼저 적용할 UTF-8 인코딩 필터를 생성
    public CharacterEncodingFilter encodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }

    // JWT 필터, 예외 처리, URL 인증 규칙, 무상태 세션을 설정
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager
    ) throws Exception {
        JwtUsernamePasswordAuthenticationFilter loginFilter =
                new JwtUsernamePasswordAuthenticationFilter(
                        authenticationManager,
                        refreshTokenService,
                        refreshTokenCookieManager
                );

        http
                .addFilterBefore(encodingFilter(), CsrfFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(loginFilter, UsernamePasswordAuthenticationFilter.class)
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
                                new AntPathRequestMatcher("/api/auth/login"),
                                new AntPathRequestMatcher("/api/auth/signup"),
                                new AntPathRequestMatcher("/api/auth/refresh"),
                                new AntPathRequestMatcher("/api/auth/logout"),
                                new AntPathRequestMatcher("/api/security/all"),
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
                        // TODO: 행사 관리자 인증 체계가 정의되기 전까지의 임시 조치. 별도 관리자 인증/역할 체계 도입 필요 (완료 보고 참고)
                        .requestMatchers(
                                new AntPathRequestMatcher(
                                        "/api/admin/event/start",
                                        HttpMethod.POST.name()
                                ),
                                new AntPathRequestMatcher(
                                        "/api/admin/event/finish",
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

    // Spring Security에서 사용할 BCrypt 비밀번호 인코더를 제공
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 로그인 인증에 사용할 사용자 조회 서비스와 비밀번호 인코더를 AuthenticationManager에 등록
    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
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
