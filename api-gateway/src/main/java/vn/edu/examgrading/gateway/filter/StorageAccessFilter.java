package vn.edu.examgrading.gateway.filter;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Keeps the storage proxy deliberately smaller than the MinIO API.  A presigned URL is
 * still verified by MinIO; this filter only prevents bucket listing, admin APIs and
 * accidental forwarding of application credentials.
 */
@Component
public final class StorageAccessFilter implements WebFilter, Ordered {
    private static final String STORAGE_PREFIX = "/storage";
    private static final String ORIGINAL_POST_PATH = "/storage/original";
    private static final Pattern OBJECT = Pattern.compile(
        "/storage/(?:original|anonymized)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            + "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/v[1-9][0-9]*\\.pdf");
    private static final Set<String> ALLOWED_SIGNED_QUERY_KEYS = Set.of(
        "x-amz-algorithm", "x-amz-credential", "x-amz-date", "x-amz-expires", "x-amz-signedheaders",
        "x-amz-signature", "response-content-type", "response-content-disposition");
    private static final long MAX_MULTIPART_OVERHEAD_BYTES = 65_536L;

    private final long maxPdfBytes;
    private final String upstreamHost;
    private final Set<String> corsOrigins;

    @Autowired
    public StorageAccessFilter(org.springframework.core.env.Environment environment) {
        this.maxPdfBytes = environment.getProperty("app.storage.max-pdf-bytes", Long.class, 52_428_800L);
        this.upstreamHost = environment.getProperty("app.storage.upstream-host", String.class, "localhost:9000");
        this.corsOrigins = Arrays.stream(environment.getProperty("CORS_ALLOWED_ORIGINS", "http://localhost:5173").split(","))
            .map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // Keeps focused unit tests independent of Spring's Environment.
    StorageAccessFilter(long maxPdfBytes) {
        this.maxPdfBytes = maxPdfBytes;
        this.upstreamHost = "localhost:9000";
        this.corsOrigins = Set.of("http://localhost:5173");
    }

    @Override
    @SuppressWarnings("null")
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals(STORAGE_PREFIX) && !path.startsWith(STORAGE_PREFIX + "/")) {
            return chain.filter(exchange);
        }
        applyCors(exchange);
        if (!path.equals(STORAGE_PREFIX) && !path.equals("/storage/")
            && !path.startsWith("/storage/original") && !path.startsWith("/storage/anonymized")) {
            return finish(exchange, HttpStatus.NOT_FOUND);
        }

        HttpMethod method = exchange.getRequest().getMethod();
        // Let Gateway's global CORS handler answer storage preflights.  Without
        // this, the allow-list below turns OPTIONS into 405 before CORS can add
        // Access-Control-Allow-Origin for a browser's presigned POST.
        if (method == HttpMethod.OPTIONS) {
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }
        if ((method == HttpMethod.GET || method == HttpMethod.HEAD) && !hasOnlyAllowedSignedQuery(exchange)) {
            return finish(exchange, HttpStatus.FORBIDDEN);
        }
        if (path.equals(ORIGINAL_POST_PATH) && method == HttpMethod.POST) {
            if (!exchange.getRequest().getQueryParams().isEmpty()) {
                return finish(exchange, HttpStatus.METHOD_NOT_ALLOWED);
            }
            return allowUpload(exchange, chain);
        }
        if (OBJECT.matcher(path).matches() && (method == HttpMethod.GET || method == HttpMethod.HEAD)) {
            if (!exchange.getRequest().getQueryParams().containsKey("X-Amz-Signature")) {
                return finish(exchange, HttpStatus.FORBIDDEN);
            }
            return allowStorageRequest(exchange, chain);
        }
        if ((method == HttpMethod.GET || method == HttpMethod.HEAD)
            && (path.startsWith("/storage/original/") || path.startsWith("/storage/anonymized/"))) {
            return finish(exchange, HttpStatus.NOT_FOUND);
        }
        if (path.equals(STORAGE_PREFIX) || path.equals("/storage/")
            || path.equals("/storage/original") || path.equals("/storage/anonymized")) {
            return finish(exchange, HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (path.startsWith("/storage/original/") || path.startsWith("/storage/anonymized/")) {
            return finish(exchange, HttpStatus.METHOD_NOT_ALLOWED);
        }
        return finish(exchange, HttpStatus.NOT_FOUND);
    }

    private Mono<Void> allowUpload(ServerWebExchange exchange, WebFilterChain chain) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            return finish(exchange, HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (contentLength > maxPdfBytes + MAX_MULTIPART_OVERHEAD_BYTES) {
            return finish(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return allowStorageRequest(exchange, chain);
    }

    @SuppressWarnings("null")
    private boolean hasOnlyAllowedSignedQuery(@NonNull ServerWebExchange exchange) {
        return exchange.getRequest().getQueryParams().keySet().stream()
            .map(String::toLowerCase)
            .allMatch(ALLOWED_SIGNED_QUERY_KEYS::contains);
    }

    private Mono<Void> allowStorageRequest(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                headers.remove(HttpHeaders.COOKIE);
                headers.keySet().removeIf(name -> {
                    String normalized = name.toLowerCase();
                    return normalized.startsWith("x-user-") || normalized.startsWith("x-service-")
                        || normalized.startsWith("x-forwarded-") || normalized.equals("x-identity-signature");
                });
                headers.set(HttpHeaders.HOST, upstreamHost);
            })
            .build();
        exchange.getResponse().getHeaders().setCacheControl("private, no-store");
        exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponse().beforeCommit(() -> {
            // Upstream MinIO may add its own CORS values. Set exactly one final
            // browser-facing value after the proxied response has been copied.
            applyCors(exchange);
            exchange.getResponse().getHeaders().remove(HttpHeaders.SERVER);
            exchange.getResponse().getHeaders().keySet().removeIf(name -> name.toLowerCase().startsWith("x-minio-"));
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    private Mono<Void> finish(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private void applyCors(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null || !corsOrigins.contains(origin)) return;
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setAccessControlAllowOrigin(origin);
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        headers.setAccessControlAllowMethods(java.util.List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.OPTIONS));
        headers.setAccessControlAllowHeaders(java.util.List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
            "Idempotency-Key", "X-Request-Id"));
        headers.setAccessControlExposeHeaders(java.util.List.of("X-Request-Id", HttpHeaders.ETAG));
        headers.setAccessControlMaxAge(3600L);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
