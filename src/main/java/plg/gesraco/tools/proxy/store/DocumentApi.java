package plg.gesraco.tools.proxy.store;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * A document API served by the local document store (e.g. the standard Alfresco REST API).
 *
 * <p>Implementations recognize the requests of their API, answer them from local copies of the nodes (see
 * {@link LocalNodes}) and apply writes to those copies only. They are created by a {@link DocumentApiProvider}
 * selected by name in the configuration ({@code local-store.apis}).
 */
public interface DocumentApi {

    /** Provider name, as used in the configuration. */
    String name();

    /**
     * Handles the request when it belongs to this API and returns its outcome, or returns null (without touching
     * the response) for requests it doesn't own. The caller then proxies reads and rejects writes.
     */
    Outcome handle(HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * Node addressed by a (decoded) request URI in this API's own syntax, or null. Generic node references
     * ({@code workspace://SpacesStore/<uuid>}, {@code /workspace/SpacesStore/<uuid>}) are recognized by the store
     * already.
     */
    default NodeRef nodeIn(String decodedUri) {
        return null;
    }

    /** Glob paths of POST requests of this API that only read data (searches, bulk reads). */
    default List<String> readOnlyPostPaths() {
        return List.of();
    }

    /** Local copies of this API's nodes. */
    LocalNodes nodes();
}
