package plg.gesraco.tools.proxy.logging;

/**
 * Lightweight, zero-dependency utilities for RFC 8259 JSON escaping and formatting.
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /**
     * Escapes a string in accordance with the JSON specification (RFC 8259).
     */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch <= '\u001f') {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Formats a textual JSON body.
     * If the text is already a JSON object or array, it is preserved as raw JSON.
     * Otherwise, it is enclosed in quotes after escaping.
     */
    public static String formatJsonBody(String body) {
        String trimmed = body.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed;
        }
        return "\"" + escape(trimmed) + "\"";
    }
}
