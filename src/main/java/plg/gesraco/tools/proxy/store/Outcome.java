package plg.gesraco.tools.proxy.store;

/**
 * Outcome of a request answered by the local document store, used for the audit record.
 * {@code targetUrl} / {@code fromFallback} describe the last backend call made to copy data, if any.
 */
public record Outcome(int status, String targetUrl, boolean fromFallback, String requestBody) {
}
