package plg.gesraco.tools.proxy.store;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import plg.gesraco.tools.proxy.config.LocalStoreConfig;
import plg.gesraco.tools.proxy.config.LocalStoreConfig.DocumentApiConfig;
import plg.gesraco.tools.proxy.http.Http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The local store: the configured routes ({@link LocalRoutes}) first, then the document APIs in configuration order
 * (the first API that owns a request handles it).
 */
public final class LocalDocuments {

    private final List<DocumentApi> apis;
    private final List<String> readOnlyPostPaths;
    private final LocalRoutes routes;

    public LocalDocuments(List<DocumentApi> apis, List<String> configuredReadOnlyPostPaths, LocalRoutes routes) {
        this.apis = List.copyOf(apis);
        this.routes = routes;
        List<String> paths = new ArrayList<>(configuredReadOnlyPostPaths);
        apis.forEach(api -> paths.addAll(api.readOnlyPostPaths()));
        this.readOnlyPostPaths = List.copyOf(paths);
    }

    /**
     * Creates the APIs of {@code config.apis()} from the providers found by {@code loader}. Each API keeps its
     * copies in its {@code directory} setting, by default {@code <local-store.directory>/<name>}.
     *
     * @throws IllegalArgumentException when an API has no provider
     */
    public static LocalDocuments create(LocalStoreConfig config, Upstream upstream, int maxLoggableTextBytes,
                                        ClassLoader loader) {
        Map<String, DocumentApiProvider> providers = new LinkedHashMap<>();
        ServiceLoader.load(DocumentApiProvider.class, loader).forEach(p -> providers.putIfAbsent(p.name(), p));

        List<DocumentApi> apis = new ArrayList<>();
        for (DocumentApiConfig api : config.apis()) {
            DocumentApiProvider provider = providers.get(api.name());
            if (provider == null) {
                throw new IllegalArgumentException("No document API named '" + api.name()
                        + "' (local-store.apis). Available: " + providers.keySet()
                        + ". Plugins are loaded from the plugins directory (plugins-directory).");
            }
            String directory = api.setting("directory", null);
            Path root = directory != null ? Path.of(directory) : config.directory().resolve(api.name());
            apis.add(provider.create(new DocumentApiProvider.Context(new LocalDocumentStore(root), upstream,
                    config.copyContentEagerly(), maxLoggableTextBytes, api)));
        }
        LocalRoutes routes = new LocalRoutes(config.routes(), config.directory().resolve("routes"), upstream,
                maxLoggableTextBytes);
        return new LocalDocuments(apis, config.readOnlyPostPaths(), routes);
    }

    /**
     * Class loader over the jars of {@code pluginsDirectory} (missing directory: the application class loader).
     */
    public static ClassLoader pluginClassLoader(Path pluginsDirectory) {
        ClassLoader parent = LocalDocuments.class.getClassLoader();
        if (pluginsDirectory == null || !Files.isDirectory(pluginsDirectory)) {
            return parent;
        }
        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            List<URL> jars = new ArrayList<>();
            for (Path jar : files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                jars.add(jar.toUri().toURL());
            }
            return jars.isEmpty() ? parent : new URLClassLoader(jars.toArray(URL[]::new), parent);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<DocumentApi> apis() {
        return apis;
    }

    public LocalRoutes routes() {
        return routes;
    }

    public Outcome handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (routes != null) {
            Outcome outcome = routes.handle(request, response);
            if (outcome != null || routes.proxies(request.getMethod(), Http.path(request))) {
                return outcome;
            }
        }
        for (DocumentApi api : apis) {
            Outcome outcome = api.handle(request, response);
            if (outcome != null) {
                return outcome;
            }
        }
        return null;
    }

    /**
     * Whether the request would modify data on a backend (configured and API read-only POSTs, and routes in proxy
     * mode, excepted).
     */
    public boolean isWrite(String method, String requestPath) {
        if (routes != null && routes.proxies(method, requestPath)) {
            return false;
        }
        return LocalStoreConfig.isWrite(method, requestPath, readOnlyPostPaths);
    }

    /** Node referenced by the URI whose local copy came from the fallback target, or null. */
    public NodeRef fallbackNodeIn(String relativeUri) throws IOException {
        for (DocumentApi api : apis) {
            NodeRef ref = api.nodes().fallbackNodeIn(relativeUri, api::nodeIn);
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }
}
