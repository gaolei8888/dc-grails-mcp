package grails.plugin.mcp.tools

import grails.plugin.mcp.AppInspectorService
import grails.plugin.mcp.McpAuditService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
@CompileDynamic
class AppInspectionTools {

    @Autowired
    AppInspectorService appInspectorService

    @Autowired
    McpAuditService mcpAuditService

    @Tool(name = "gr_config",
             description = """Get the resolved runtime application configuration.
Returns flattened key-value properties from the running Grails app. Sensitive values (passwords, secrets, keys, tokens, credentials) are automatically redacted.
Use the prefix parameter to filter to a specific config section.""")
    String getAppConfig(
            @ToolParam(description = "Filter config keys by prefix, e.g. 'grails.mail' or 'spring.datasource'")
            String prefix) {

        mcpAuditService.log('mcp-client', 'get_app_config', [prefix: prefix ?: ''])
        def result = appInspectorService.getConfig(prefix ?: '')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "gr_beans",
             description = """List all Spring beans registered in the ApplicationContext.
Returns bean name and fully-qualified class type. Supports filtering by bean name or type.""")
    String getSpringBeans(
            @ToolParam(description = "Filter beans by name (case-insensitive substring match)")
            String filter,
            @ToolParam(description = "Filter beans by type (case-insensitive substring match)")
            String typeFilter) {

        mcpAuditService.log('mcp-client', 'get_spring_beans', [filter: filter ?: '', typeFilter: typeFilter ?: ''])
        def result = appInspectorService.getBeans(filter ?: '', typeFilter ?: '')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "gr_health",
             description = """Get application health metrics.
Returns: Grails version, app name, Java version/vendor, OS info, memory usage (used/total/max MB with percentage), active thread count, available processors, and database connectivity status (product, version, sanitized URL).""")
    String appHealth() {
        mcpAuditService.log('mcp-client', 'app_health', [:])
        def result = appInspectorService.getHealth()
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
