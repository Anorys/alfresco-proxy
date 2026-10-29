package plg.gesraco.tools;

import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.server.StreamingReverseProxy;

/**
 * Main application entry point for the streaming reverse proxy.
 *
 * <p>Command-line usage:
 * {@code java -jar alfresco-proxy.jar --port=9081 --target=http://alfresco:8080 --log-file=requests.json}
 */
public final class StreamingReverseProxyLogger {

    private StreamingReverseProxyLogger() {
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.fromArgs(args);
        new StreamingReverseProxy(config).start(true);
    }
}
