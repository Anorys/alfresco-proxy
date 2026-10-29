package plg.gesraco.tools.proxy.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import plg.gesraco.tools.proxy.config.ProxyConfig;

import java.net.http.HttpRequest;

/**
 * Standard proxy interceptor that injects standard reverse proxy headers (X-Forwarded-*).
 * Essential for Alfresco to build correct absolute resource URLs in API and WebScript responses.
 */
public class StandardForwardingInterceptor implements ProxyInterceptor {

    @Override
    public void onRequest(HttpServletRequest request, HttpRequest.Builder targetRequest, ProxyConfig config) {
        if (!config.addForwardedHeaders()) {
            return;
        }
        targetRequest.header("X-Forwarded-For", request.getRemoteAddr());
        targetRequest.header("X-Forwarded-Proto", "http");

        String host = request.getHeader("Host");
        if (host != null) {
            targetRequest.header("X-Forwarded-Host", host);
        }
    }
}
