package plg.gesraco.tools.proxy.store;

/**
 * Content file written to the store.
 */
public record StoredContent(String fileName, long size, String mimetype) {
}
