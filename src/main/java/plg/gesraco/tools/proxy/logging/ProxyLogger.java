package plg.gesraco.tools.proxy.logging;

import plg.gesraco.tools.proxy.model.ProxyAuditRecord;

/**
 * Functional extension interface to plug in custom audit logging sinks
 * (e.g. JSON Lines file, Elasticsearch, Kafka, Console, Datadog).
 */
@FunctionalInterface
public interface ProxyLogger {
    void log(ProxyAuditRecord record);
}
