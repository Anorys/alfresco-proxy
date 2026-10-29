package plg.gesraco.tools.proxy.server;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.handler.ReverseProxyHandler;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;
import plg.gesraco.tools.proxy.interceptor.ProxyInterceptor;
import plg.gesraco.tools.proxy.interceptor.StandardForwardingInterceptor;
import plg.gesraco.tools.proxy.logging.JsonFileProxyLogger;
import plg.gesraco.tools.proxy.logging.ProxyLogger;
import plg.gesraco.tools.proxy.store.DocumentApi;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Reverse proxy server built on Jetty 12 (one virtual thread per request) and {@code java.net.http.HttpClient}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Custom routes ({@code customRoutes}), answered before anything else and not audited.</li>
 *   <li>Selective request interception via {@link DirectRequestHandler} to respond directly.</li>
 *   <li>Streaming reverse proxying for all other requests to Alfresco.</li>
 *   <li>Target and fallback request blocking rules (e.g. block PUT, block paths).</li>
 *   <li>Automatic fallback forwarding if primary server returns an error, fails, or blocks a request.</li>
 *   <li>Configurable Basic Authentication overwrite for target and fallback target servers.</li>
 *   <li>Responses always streamed; request bodies streamed unless a fallback needs to replay them.</li>
 *   <li>Full NDJSON audit logging with configurable sensitive header masking.</li>
 * </ul>
 */
public class StreamingReverseProxy {

    private final ProxyConfig config;
    private final List<DirectRequestHandler> directHandlers;
    private final List<DirectRequestHandler> customRoutes;
    private final HttpClient httpClient;
    private final ReverseProxyHandler proxyHandler;
    private Server server;

    public StreamingReverseProxy(ProxyConfig config, List<DirectRequestHandler> directHandlers,
                                 List<ProxyInterceptor> interceptors, ProxyLogger logger,
                                 List<DirectRequestHandler> customRoutes) {
        this.config = config;
        this.directHandlers = List.copyOf(directHandlers);
        this.customRoutes = List.copyOf(customRoutes);
        this.httpClient = ReverseProxyHandler.newHttpClient(config);
        this.proxyHandler = new ReverseProxyHandler(config, httpClient, logger, interceptors, directHandlers);
    }

    public StreamingReverseProxy(ProxyConfig config, ProxyLogger logger) {
        this(config, config.directHandlers(), List.of(new StandardForwardingInterceptor()), logger, List.of());
    }

    public StreamingReverseProxy(ProxyConfig config) {
        this(config, new JsonFileProxyLogger(config));
    }

    public ProxyConfig config() {
        return config;
    }

    public ReverseProxyHandler proxyHandler() {
        return proxyHandler;
    }

    /** Port the server listens on (useful with {@code localPort} 0 in tests), or -1 when not started. */
    public int port() {
        return server == null ? -1 : ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /**
     * Starts the reverse proxy server.
     *
     * @param port listening port (0 = any free port)
     * @param wait if true, blocks the current thread until the server terminates
     */
    public void start(int port, boolean wait) throws Exception {
        QueuedThreadPool threads = new QueuedThreadPool();
        threads.setName("proxy");
        threads.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Server jetty = new Server(threads);

        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendDateHeader(false);
        ServerConnector connector = new ServerConnector(jetty, new HttpConnectionFactory(http));
        connector.setPort(port);
        jetty.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder("proxy", new ProxyServlet());
        // Multipart bodies are only parsed by the local document store (uploads); parts above 1 MB go to disk
        holder.getRegistration().setMultipartConfig(new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), -1, -1, 1024 * 1024));
        context.addServlet(holder, "/*");
        jetty.setHandler(context);

        server = jetty;
        jetty.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        printBanner();
        if (wait) {
            jetty.join();
        }
    }

    public void start(boolean wait) throws Exception {
        start(config.localPort(), wait);
    }

    private void printBanner() {
        System.out.println("=================================================");
        System.out.println(" Proxy started on  : http://localhost:" + port());
        String targetAuth = config.normalizedTargetBasicAuth() != null ? " (Basic Auth enabled)" : "";
        System.out.println(" Target            : " + config.normalizedTargetBaseUrl() + targetAuth);
        if (!config.targetBlockedRequests().isEmpty()) {
            System.out.println(" Target Blocked    : " + config.targetBlockedRequests().size() + " rules configured");
        }
        if (config.normalizedFallbackTargetBaseUrl() != null) {
            String fallbackAuth = config.normalizedFallbackTargetBasicAuth() != null ? " (Basic Auth enabled)" : "";
            System.out.println(" Fallback Target   : " + config.normalizedFallbackTargetBaseUrl() + fallbackAuth);
            if (!config.fallbackTargetBlockedRequests().isEmpty()) {
                System.out.println(" Fallback Blocked  : " + config.fallbackTargetBlockedRequests().size()
                        + " rules configured");
            }
        }
        if (proxyHandler.localDocuments() != null) {
            String apis = proxyHandler.localDocuments().apis().stream()
                    .map(api -> api.name() + " -> " + api.nodes().store().root().toAbsolutePath())
                    .collect(Collectors.joining(", "));
            System.out.println(" Local store       : " + apis + " (writes never reach Alfresco)");
            int routes = proxyHandler.localDocuments().routes().routes().size();
            if (routes > 0) {
                System.out.println(" Local routes      : " + routes + " rules -> "
                        + proxyHandler.localDocuments().routes().root().toAbsolutePath());
            }
        }
        System.out.println(" Direct handlers   : " + directHandlers.size() + " registered");
        System.out.println(" Audit log file    : " + config.logFile().toAbsolutePath());
        System.out.println(" Engine            : Jetty 12 (virtual threads) + java.net.http.HttpClient");
        System.out.println("=================================================\n");
    }

    /**
     * Stops the reverse proxy server and closes the outbound HTTP client.
     */
    public void stop() {
        try {
            Server s = server;
            if (s != null && s.isRunning()) {
                s.setStopTimeout(2000);
                s.stop();
                System.out.println("[Proxy stopped]");
            }
            httpClient.close();
        } catch (Exception e) {
            System.err.println("[ERR] Error stopping proxy: " + e.getMessage());
        }
    }

    /** Names of the document APIs served by the local store (empty when disabled). */
    public List<String> documentApis() {
        return proxyHandler.localDocuments() == null
                ? List.of()
                : proxyHandler.localDocuments().apis().stream().map(DocumentApi::name).toList();
    }

    private final class ProxyServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            for (DirectRequestHandler route : customRoutes) {
                if (route.handle(request, response)) {
                    return;
                }
            }
            proxyHandler.handle(request, response);
        }
    }
}
