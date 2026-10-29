package plg.gesraco.tools.proxy.store;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * Backend access used by the local store to copy nodes (primary target first, then fallback).
 * The response passed to the consumer is only valid inside the callback.
 */
public interface Upstream {

    /** Reads a backend response; its body stream is closed after the call. */
    @FunctionalInterface
    interface ResponseConsumer<T> {
        T consume(HttpResponse<InputStream> response) throws IOException;
    }

    /**
     * Sends {@code method relativeUri} (with an optional JSON body) to the backends on behalf of the client
     * {@code request}: its headers are forwarded, credentials are replaced as configured.
     */
    <T> UpstreamResult<T> request(HttpServletRequest request, String method, String relativeUri, String jsonBody,
                                  ResponseConsumer<T> consumer) throws IOException;
}
