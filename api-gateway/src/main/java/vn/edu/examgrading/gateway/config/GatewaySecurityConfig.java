package vn.edu.examgrading.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .pathMatchers("/.well-known/jwks.json", "/storage/original/**", "/storage/anonymized/**", "/actuator/health", "/actuator/info").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/reviews/**").hasAnyRole("EXAM_OFFICE", "COUNCIL")
                .pathMatchers(HttpMethod.GET, "/api/v1/grading/**").hasRole("GRADER")
                .pathMatchers(HttpMethod.GET, "/api/v1/exams/*/results/**").hasRole("EXAM_OFFICE")
                .pathMatchers("/api/v1/exams/*/candidates/**").hasRole("EXAM_OFFICE")
                .pathMatchers(HttpMethod.GET, "/api/v1/exams/**").hasAnyRole("EXAM_OFFICE", "GRADER", "COUNCIL")
                .pathMatchers(HttpMethod.POST, "/api/v1/reviews/*/decision", "/api/v1/reviews/*/council-decision").hasRole("COUNCIL")
                .pathMatchers(HttpMethod.POST, "/api/v1/exams/**", "/api/v1/submissions/**").hasRole("EXAM_OFFICE")
                .pathMatchers(HttpMethod.PUT, "/api/v1/exams/**", "/api/v1/grading/**").hasRole("EXAM_OFFICE")
                .anyExchange().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))).build();
    }
}
