package plg.gesraco.tools.proxy.store;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alfresco node reference ({@code workspace://SpacesStore/<guid>}).
 * Segments are restricted to path-safe characters because they are used as directory names:
 * create instances with {@link #of} or {@link #parse}, which return null for unsafe values.
 */
public record NodeRef(String space, String store, String guid) {

    public static final String DEFAULT_SPACE = "workspace";
    public static final String DEFAULT_STORE = "SpacesStore";

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern NODE_REF = Pattern.compile("^([^:/]+)://([^/]+)/([^/]+)$");

    public static boolean isSafeSegment(String segment) {
        return segment != null && SAFE_SEGMENT.matcher(segment).matches();
    }

    public static NodeRef of(String space, String store, String guid) {
        return isSafeSegment(space) && isSafeSegment(store) && isSafeSegment(guid)
                ? new NodeRef(space, store, guid)
                : null;
    }

    /** Node of the default store ({@code workspace://SpacesStore}), as addressed by a bare node id. */
    public static NodeRef ofId(String id) {
        return of(DEFAULT_SPACE, DEFAULT_STORE, id);
    }

    public static NodeRef parse(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = NODE_REF.matcher(value.trim());
        return m.matches() ? of(m.group(1), m.group(2), m.group(3)) : null;
    }

    /** Same node in another store, keeping the guid. */
    public NodeRef inStoreOf(NodeRef other) {
        return new NodeRef(other.space, other.store, guid);
    }

    @Override
    public String toString() {
        return space + "://" + store + "/" + guid;
    }
}
