package grails.plugin.mcp

import grails.plugins.Plugin

class GrailsMcpGrailsPlugin extends Plugin {

    def grailsVersion = '7.0.0 > *'
    def title = 'Grails MCP Plugin'
    def author = 'Lei Gao'
    def authorEmail = 'gaolei8888@yahoo.com'
    def description = 'AI-native MCP server for Claude Code — exposes Groovy execution, domain model, database, and logs via Streamable HTTP MCP protocol.'
    def documentation = 'https://github.com/nicetool/dc-grails-mcp'  // update to your repo URL
    def license = 'MIT'

    Closure doWithSpring() { { ->
        // Register MCP tool beans
        groovyExecutionTools(grails.plugin.mcp.tools.GroovyExecutionTools) {
            groovyExecutorService = ref('groovyExecutorService')
            mcpAuditService = ref('mcpAuditService')
        }
        domainInspectionTools(grails.plugin.mcp.tools.DomainInspectionTools) {
            domainInspectorService = ref('domainInspectorService')
        }
        databaseTools(grails.plugin.mcp.tools.DatabaseTools) {
            databaseInspectorService = ref('databaseInspectorService')
            mcpAuditService = ref('mcpAuditService')
        }
        logTools(grails.plugin.mcp.tools.LogTools) {
            logReaderService = ref('logReaderService')
        }
        appInspectionTools(grails.plugin.mcp.tools.AppInspectionTools) {
            appInspectorService = ref('appInspectorService')
        }

        // Register the event listener that will add tools to MCP server after context is ready
        mcpToolRegistrar(grails.plugin.mcp.McpToolRegistrar)
    } }

    void doWithApplicationContext() {
        println "Grails MCP Plugin loaded — waiting for context refresh to register tools"
    }
}
