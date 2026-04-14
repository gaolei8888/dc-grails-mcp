package grails.plugin.mcp.tools

import grails.plugin.mcp.AppInspectorService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileDynamic
class AppInspectionTools {

    @Autowired
    AppInspectorService appInspectorService

    @Tool(name = "get_app_config",
             description = """Get the resolved runtime application configuration.
Returns flattened key-value properties from the running Grails app. Sensitive values (passwords, secrets, keys, tokens, credentials) are automatically redacted.
Use the prefix parameter to filter to a specific config section.""")
    String getAppConfig(
            @ToolParam(description = "Filter config keys by prefix, e.g. 'grails.mail' or 'spring.datasource'")
            String prefix) {

        def result = appInspectorService.getConfig(prefix ?: '')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "get_spring_beans",
             description = """List all Spring beans registered in the ApplicationContext.
Returns bean name and fully-qualified class type. Supports filtering by bean name or type.""")
    String getSpringBeans(
            @ToolParam(description = "Filter beans by name (case-insensitive substring match)")
            String filter,
            @ToolParam(description = "Filter beans by type (case-insensitive substring match)")
            String typeFilter) {

        def result = appInspectorService.getBeans(filter ?: '', typeFilter ?: '')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "get_app_health",
             description = """Get application health metrics.
Returns: Grails version, app name, Java version/vendor, OS info, memory usage (used/total/max MB with percentage), active thread count, available processors, and database connectivity status (product, version, sanitized URL).""")
    String appHealth() {
        def result = appInspectorService.getHealth()
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
