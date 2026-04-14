package grails.plugin.mcp

import groovy.transform.CompileDynamic
import org.slf4j.LoggerFactory

/**
 * McpAuditService
 * Records every MCP operation to a dedicated audit logger.
 * Audit logs include: caller identity, action, timestamp, truncated payload.
 *
 * Configure the audit log file via logback.xml:
 *   <appender name="MCP_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
 *     <file>logs/mcp-audit.log</file>
 *     ...
 *   </appender>
 *   <logger name="grails.plugin.mcp.audit" level="INFO" additivity="false">
 *     <appender-ref ref="MCP_AUDIT"/>
 *   </logger>
 */
@CompileDynamic
class McpAuditService {

    private static final def AUDIT_LOG = LoggerFactory.getLogger('grails.plugin.mcp.audit')
    private static final int MAX_PAYLOAD_LOG = 500

    def grailsApplication

    void log(String caller, String action, Map payload = [:], boolean success = true) {
        if (!isAuditEnabled()) return

        def truncatedPayload = payload.collectEntries { k, v ->
            def str = v?.toString() ?: ''
            [k, str.length() > MAX_PAYLOAD_LOG ? str.take(MAX_PAYLOAD_LOG) + '...' : str]
        }

        AUDIT_LOG.info(
            groovy.json.JsonOutput.toJson([
                timestamp: new Date().toInstant().toString(),
                caller   : caller,
                action   : action,
                success  : success,
                payload  : truncatedPayload,
            ])
        )
    }

    private boolean isAuditEnabled() {
        grailsApplication.config.getProperty('grails.mcp.audit.enabled', Boolean, true)
    }
}
