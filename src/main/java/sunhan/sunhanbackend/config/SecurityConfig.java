package sunhan.sunhanbackend.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.HttpBasicConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import sunhan.sunhanbackend.filter.JwtAuthenticationFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    protected SecurityFilterChain configure(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(CsrfConfigurer::disable)
                .httpBasic(HttpBasicConfigurer::disable)
                .sessionManagement(sessionManagement ->
                        sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(request -> request
                        // ⭐ 1. 관리자 전용 경로 (가장 먼저!)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ⭐ 2. 공개 경로 (인증 불필요)
                        .requestMatchers(
                                // React 앱 경로
                                "/",
                                "/sunhan-eap",
                                "/sunhan-eap/",
                                "/sunhan-eap/**",

                                // ⭐⭐⭐ React Router 경로 (중요!)
                                "/detail",
                                "/detail/",
                                "/detail/**",
                                "/admin",
                                "/admin/",
                                "/admin/**",

                                // 정적 리소스
                                "/css/**",
                                "/js/**",
                                "/static/**",
                                "/index.html",
                                "/favicon.ico",
                                "/*.ico",
                                "/*.js",
                                "/*.json",
                                "/*.png",
                                "/manifest.json",
                                "/logo192.png",

                                // 업로드 파일
                                "/uploads/**",

                                // 인증 API
                                "/api/auth/**",
                                "/api/v1/auth/**",

                                // Swagger
                                "/practice-ui.html",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api-docs/**"
                        ).permitAll()

                        // ⭐ 3. 인증 필요 경로 (API만)
                        .requestMatchers("/api/**", "/api/v1/**").authenticated()

                        // ⭐ 4. 나머지는 모두 허용 (React Router 경로들)
                        .anyRequest().permitAll()
                )

                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout/")
                        .deleteCookies("accessToken")
                        .invalidateHttpSession(true)
                )

                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(new FailedAuthenticationEntryPoint())
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return httpSecurity.build();
    }

    @Bean
    protected CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.addAllowedOrigin("http://localhost:3000");
        corsConfiguration.addAllowedOrigin("http://localhost:9090");
        corsConfiguration.addAllowedOrigin("http://100.100.100.224:9090");
        corsConfiguration.addAllowedOrigin("http://100.100.100.224:3000");
        corsConfiguration.addAllowedMethod("*");
        corsConfiguration.addAllowedHeader("*");
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.addExposedHeader("X-Total-Count");
        corsConfiguration.addExposedHeader("Content-Range");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        return source;
    }

    class FailedAuthenticationEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException, ServletException {
            String requestURI = request.getRequestURI();

            // ⭐ React Router 경로는 index.html로 리다이렉트
            if (requestURI.startsWith("/detail/") ||
                    requestURI.startsWith("/admin/") ||
                    requestURI.startsWith("/sunhan-eap/")) {
                request.getRequestDispatcher("/sunhan-eap/index.html").forward(request, response);
                return;
            }

            // API 경로는 403 JSON 응답
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"code\": \"NP\", \"message\": \"No Permission.\"}");
        }
    }
}