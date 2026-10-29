package plg.gesraco.tools.proxy.store;

/**
 * Result of an {@link Upstream} call. {@code value} is null when no backend was allowed to receive the request.
 */
public record UpstreamResult<T>(T value, int status, String targetUrl, boolean fromFallback) {
}
