package sunhan.sunhanbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
import sunhan.sunhanbackend.service.UserService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final UserService userService;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        if (requestURI.startsWith("/api-docs") || requestURI.startsWith("/swagger-ui") || requestURI.equals("/practice-ui.html")) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String token = parseBearerToken(request);
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }

            String userId = String.valueOf(jwtProvider.validate(token));
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }

            UserEntity userEntity = userRepository.findByUserId(userId).orElse(null);
            if (userEntity == null) {
                filterChain.doFilter(request, response);
                return;
            }
            // 🔥 Role 기반 권한 설정으로 변경
            List<GrantedAuthority> authorities = new ArrayList<>();

            // 1) DB의 role 필드로만 ADMIN/USER 권한 부여
            Role r = userEntity.getRole();
            if (r != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
            }

            // 2) jobLevel=1 에게만 Dept Approver 권한 추가
            if ("1".equals(userEntity.getJobLevel())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_DEPT_APPROVER"));
            }

            AbstractAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authenticationToken);
            SecurityContextHolder.setContext(securityContext);

        } catch (Exception exception) {
            exception.printStackTrace();
        }
        filterChain.doFilter(request, response);
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
