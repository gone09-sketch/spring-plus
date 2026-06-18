package org.example.expert.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.expert.domain.common.dto.AuthUser;
import org.example.expert.domain.common.exception.ServerException;
import org.example.expert.domain.user.enums.UserRole;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // JWT 검증이 필요 없는 경우 (로그인/회원가입)
        return request.getRequestURI().startsWith("/auth");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String bearerJwt = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 토큰이 없다면 SecurityConfig 의 authenticated() 규칙이 401을 처리
        if (!StringUtils.hasText(bearerJwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = jwtUtil.substringToken(bearerJwt);
            Claims claims = jwtUtil.extractClaims(jwt);

            Long userId = Long.parseLong(claims.getSubject());
            String email = claims.get("email", String.class);
            String nickname = claims.get("nickname", String.class);
            UserRole userRole = UserRole.valueOf(claims.get("userRole", String.class));

            AuthUser authUser = new AuthUser(userId, email, nickname, userRole);

            // Spring Security 가 권한 검사에 사용할 권한 목록
            List<SimpleGrantedAuthority> authorityList =
                    List.of(new SimpleGrantedAuthority(userRole.name()));

            // Authentication은 "인증된 사용자 정보"를 Spring Security에 알려주는 객체
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(authUser, null, authorityList);

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            log.warn("Expired JWT token", e);
            sendUnauthorizedResponse(response);
            return;

        } catch (SecurityException | MalformedJwtException e) {
            log.warn("Invalid JWT signature or malformed JWT token", e);
            sendUnauthorizedResponse(response);
            return;

        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT token", e);
            sendUnauthorizedResponse(response);
            return;

        } catch (ServerException e) {
            log.warn("JWT token prefix is missing or invalid", e);
            sendUnauthorizedResponse(response);
            return;

        } catch (IllegalArgumentException e) {
            log.warn("Invalid JWT claims", e);
            sendUnauthorizedResponse(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 외부 에러 메세지를 통일화하여 보안성을 높인다
     */
    private void sendUnauthorizedResponse(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(
                HttpServletResponse.SC_UNAUTHORIZED,
                "유효하지 않은 JWT 토큰입니다."
        );
    }
}
