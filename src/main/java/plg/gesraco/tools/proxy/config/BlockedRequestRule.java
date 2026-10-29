package plg.gesraco.tools.proxy.config;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule defining requests to block based on HTTP method and/or URL path pattern.
 *
 * @param path    exact path or glob ({@code *} = one segment, {@code **} = recursive); null matches all paths
 * @param methods methods to block (case-insensitive); empty matches all methods
 */
public record BlockedRequestRule(String path, List<String> methods, int status, String message) {

    public static final String DEFAULT_MESSAGE = "Forbidden: Request blocked by proxy configuration";

    public BlockedRequestRule {
        methods = methods == null ? List.of() : List.copyOf(methods);
        message = message == null ? DEFAULT_MESSAGE : message;
    }

    public static BlockedRequestRule ofPath(String path) {
        return new BlockedRequestRule(path, List.of(), 403, DEFAULT_MESSAGE);
    }

    public static BlockedRequestRule ofMethods(String... methods) {
        return new BlockedRequestRule(null, List.of(methods), 403, DEFAULT_MESSAGE);
    }

    public static BlockedRequestRule of(String path, String... methods) {
        return new BlockedRequestRule(path, List.of(methods), 403, DEFAULT_MESSAGE);
    }

    /**
     * Checks whether an incoming HTTP request matches this blocking rule.
     */
    public boolean matches(String method, String requestPath) {
        // 1. Method check: if methods list is specified, method must match
        boolean methodMatches = methods.isEmpty() || methods.stream().anyMatch(m -> m.equalsIgnoreCase(method));
        if (!methodMatches) {
            return false;
        }

        // 2. Path check: if path is null or empty, matches all paths
        String p = path == null ? null : path.trim();
        return p == null || p.isEmpty() || p.equals("*") || p.equals("/**")
                || matchesPathPattern(p, requestPath.trim());
    }

    /**
     * Checks if {@code actualPath} matches {@code pattern} (exact or wildcard glob). The query string is ignored.
     */
    public static boolean matchesPathPattern(String pattern, String actualPath) {
        int q = actualPath.indexOf('?');
        String cleanActual = q < 0 ? actualPath : actualPath.substring(0, q);
        if (pattern.equals(cleanActual)) {
            return true;
        }
        return globToRegex(pattern).matcher(cleanActual).matches();
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**", i)) {
                sb.append(".*");
                i += 2;
                continue;
            }
            switch (c) {
                case '*' -> sb.append("[^/]*");
                case '?' -> sb.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
            i++;
        }
        return Pattern.compile(sb.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }
}
