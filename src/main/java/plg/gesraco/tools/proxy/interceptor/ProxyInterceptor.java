package plg.gesraco.tools.proxy.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import plg.gesraco.tools.proxy.config.ProxyConfig;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Interceptor interface allowing inspection or modification of HTTP requests and responses
 * during proxying (e.g. adding security headers, distributed tracing correlation IDs, metrics).
 */
public interface ProxyInterceptor {

    /**
     * Called before the proxied request is dispatched to the target server.
     */
    default void onRequest(HttpServletRequest request, HttpRequest.Builder targetRequest, ProxyConfig config) {
    }

    /**
     * Called after receiving the response from the target server, before streaming to the client.
     */
    default void onResponse(HttpServletRequest request, HttpResponse<?> targetResponse, ProxyConfig config) {
    }
}
