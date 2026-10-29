package plg.gesraco.tools.proxy.store;

import plg.gesraco.tools.proxy.config.LocalStoreConfig.DocumentApiConfig;

/**
 * Service provider creating a {@link DocumentApi}. Providers are discovered with {@link java.util.ServiceLoader}
 * ({@code META-INF/services/plg.gesraco.tools.proxy.store.DocumentApiProvider}) on the classpath and in the jars of
 * the plugins directory, and selected by {@link #name()} in {@code local-store.apis}.
 */
public interface DocumentApiProvider {

    String name();

    DocumentApi create(Context context);

    /**
     * What a document API gets from the proxy.
     *
     * @param store               the API's own store (each API keeps its copies in a separate directory)
     * @param upstream            backend access (primary target, then fallback)
     * @param copyContentEagerly  whether content is copied together with the node
     * @param maxLoggableTextBytes limit for request bodies written to the audit log
     * @param config              the API's entry of {@code local-store.apis}
     */
    record Context(LocalDocumentStore store, Upstream upstream, boolean copyContentEagerly,
                   int maxLoggableTextBytes, DocumentApiConfig config) {
    }
}
