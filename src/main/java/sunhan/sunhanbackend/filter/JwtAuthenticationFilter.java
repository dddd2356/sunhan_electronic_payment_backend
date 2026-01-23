package sunhan.sunhanbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.provider.JwtProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();

        // ⭐ 공개 경로는 필터 통과 (JWT 검증 스킵)
        if (isPublicPath(requestURI)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = parseBearerToken(request);
            if (token == null) {
                log.debug("No token found for URI: {}", requestURI);
                filterChain.doFilter(request, response);
                return;
            }

            String userId = jwtProvider.validate(token);
            if (userId == null) {
                log.warn("Invalid token for URI: {}", requestURI);
                filterChain.doFilter(request, response);
                return;
            }

            UserEntity userEntity = userRepository.findByUserIdNoCache(userId).orElse(null);
            if (userEntity == null) {
                log.warn("User not found: {}", userId);
                filterChain.doFilter(request, response);
                return;
            }

            // 권한 설정
            List<GrantedAuthority> authorities = new ArrayList<>();

            Role userRole = userEntity.getRole();
            if (userRole != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + userRole.name()));
            } else {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }

            // jobLevel 기반 추가 권한
            if ("1".equals(userEntity.getJobLevel())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_DEPT_APPROVER"));
            }

            log.info("🔐 Authenticated - User: {}, Role: {}, URI: {}",
                    userId, userRole, requestURI);

            AbstractAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authenticationToken);
            SecurityContextHolder.setContext(securityContext);

        } catch (Exception exception) {
            log.error("❌ JWT Filter Error: {}", exception.getMessage());
            exception.printStackTrace();
        }

        filterChain.doFilter(request, response);
    }

    // ⭐ 공개 경로 판단 메서드
    private boolean isPublicPath(String uri) {
        return uri.startsWith("/sunhan-eap/") ||
                uri.startsWith("/detail/") ||  // ⭐ React Router
                uri.startsWith("/admin/") ||   // ⭐ React Router
                uri.startsWith("/css/") ||
                uri.startsWith("/js/") ||
                uri.startsWith("/static/") ||
                uri.startsWith("/api/v1/auth/") ||
                uri.startsWith("/api-docs") ||
                uri.startsWith("/swagger-ui") ||
                uri.equals("/") ||
                uri.equals("/practice-ui.html") ||
                uri.equals("/favicon.ico") ||
                uri.endsWith(".ico") ||
                uri.endsWith(".js") ||
                uri.endsWith(".json") ||
                uri.endsWith(".png");
    }

    private String parseBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        if (token.chars().filter(ch -> ch == '.').count() != 2) {
            return null;
        }
        return token;
    }
}