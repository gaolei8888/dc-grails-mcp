package grails.plugin.mcp

import groovy.transform.CompileDynamic
import io.modelcontextprotocol.server.McpSyncServer
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent

/**
 * Discovers all beans with @McpTool methods — from both the plugin AND the host app —
 * and registers them with the MCP server after the context is fully initialized.
 *
 * Host apps can define their own @McpTool beans in services or components,
 * and they will be auto-registered alongside the plugin's built-in tools.
 */
@CompileDynamic
class McpToolRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    private boolean registered = false

    @Override
    void onApplicationEvent(ContextRefreshedEvent event) {
        if (registered) return
        registered = true

        ApplicationContext ctx = event.applicationContext

        // Check if MCP server is available
        McpSyncServer mcpServer
        try {
            mcpServer = ctx.getBean(McpSyncServer)
        } catch (Exception e) {
            println "Grails MCP Plugin: McpSyncServer bean not found, skipping tool registration"
            return
        }

        // Strategy 1: Discover beans by known plugin bean names
        def knownToolBeans = ['groovyExecutionTools', 'domainInspectionTools', 'databaseTools', 'logTools', 'appInspectionTools']
        def toolBeans = []
        knownToolBeans.each { name ->
            try {
                toolBeans << ctx.getBean(name)
                println "Grails MCP Plugin: Found plugin tool bean '${name}'"
            } catch (Exception e) {
                println "Grails MCP Plugin: Plugin bean '${name}' not found: ${e.message}"
            }
        }

        // Strategy 2: Check bean definitions for @McpTool without instantiating all beans
        // Only instantiate beans whose CLASS has @McpTool methods (safe, no side effects)
        ctx.beanDefinitionNames.each { beanName ->
            if (beanName in knownToolBeans) return  // already added
            try {
                def beanDef = ctx.getBeanFactory().getBeanDefinition(beanName)
                String className = beanDef.beanClassName
                if (!className) return
                Class clazz = Class.forName(className, false, Thread.currentThread().contextClassLoader)
                if (hasToolMethodsOnClass(clazz)) {
                    toolBeans << ctx.getBean(beanName)
                    println "Grails MCP Plugin: Found host app tool bean '${beanName}'"
                }
            } catch (Exception ignored) {}
        }

        if (toolBeans.isEmpty()) {
            println "Grails MCP Plugin: No @McpTool beans found, skipping registration"
            return
        }

        println "Grails MCP Plugin: Registering tools from ${toolBeans.size()} bean(s)..."

        // Debug: check what MethodToolCallbackProvider sees
        toolBeans.each { bean ->
            println "Grails MCP Plugin: DEBUG bean class: ${bean.getClass().name}"
            bean.getClass().declaredMethods.each { m ->
                def annotations = m.annotations.collect { it.annotationType().simpleName }
                if (annotations) {
                    println "Grails MCP Plugin: DEBUG   method ${m.name} annotations: ${annotations}"
                }
            }
            // Also check superclass
            def superClass = bean.getClass().superclass
            if (superClass && superClass != Object) {
                println "Grails MCP Plugin: DEBUG superclass: ${superClass.name}"
                superClass.declaredMethods.each { m ->
                    def annotations = m.annotations.collect { it.annotationType().simpleName }
                    if (annotations) {
                        println "Grails MCP Plugin: DEBUG   super method ${m.name} annotations: ${annotations}"
                    }
                }
            }
        }

        // Convert @McpTool annotated methods to ToolCallbacks
        ToolCallback[] callbacks
        try {
            callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build()
                .getToolCallbacks()
            println "Grails MCP Plugin: MethodToolCallbackProvider returned ${callbacks.length} callbacks"
        } catch (Exception e) {
            println "Grails MCP Plugin: ERROR from MethodToolCallbackProvider: ${e.message}"
            e.printStackTrace()
            return
        }

        // Register each tool with the MCP server
        def pluginTools = []
        def hostTools = []
        callbacks.each { callback ->
            def spec = McpToolUtils.toSyncToolSpecification(callback)
            mcpServer.addTool(spec)
            def toolName = callback.toolDefinition?.name() ?: callback.name ?: 'unknown'
            if (toolName.startsWith('gr_')) {
                pluginTools << toolName
            } else {
                hostTools << toolName
            }
        }

        // Notify clients that tools list changed
        mcpServer.notifyToolsListChanged()

        // Print banner
        def endpoint = '/mcp'
        try {
            endpoint = ctx.getEnvironment().getProperty('spring.ai.mcp.server.streamable-http.mcp-endpoint', '/mcp')
        } catch (ignored) {}

        println ''
        println '╔══════════════════════════════════════════════════════════════╗'
        println '║              Grails MCP Plugin — Ready                      ║'
        println '╠══════════════════════════════════════════════════════════════╣'
        println "║  Endpoint:  ${endpoint.padRight(48)}║"
        println "║  Tools:     ${callbacks.length} registered".padRight(63) + '║'
        println '╠══════════════════════════════════════════════════════════════╣'
        if (pluginTools) {
            println '║  Built-in tools:                                             ║'
            pluginTools.each { name ->
                println "║    - ${name.padRight(55)}║"
            }
        }
        if (hostTools) {
            println '║  Host app tools:                                             ║'
            hostTools.each { name ->
                println "║    - ${name.padRight(55)}║"
            }
        }
        println '╠══════════════════════════════════════════════════════════════╣'
        println '║  Claude Code config (.mcp.json):                            ║'
        println '║    { "mcpServers": { "grails": {                            ║'
        println '║        "type": "http",                                      ║'
        println "║        \"url\": \"http://localhost:<port>${endpoint}\"".padRight(63) + '║'
        println '║    }}}                                                      ║'
        println '╚══════════════════════════════════════════════════════════════╝'
        println ''
    }

    /**
     * Check if a CLASS has any methods annotated with @McpTool.
     * Does NOT instantiate the bean — safe to call on any class.
     * Walks up the class hierarchy to handle proxies.
     */
    private boolean hasToolMethodsOnClass(Class clazz) {
        try {
            while (clazz != null && clazz != Object) {
                if (clazz.declaredMethods.any { it.isAnnotationPresent(Tool) }) {
                    return true
                }
                clazz = clazz.superclass
            }
            return false
        } catch (Exception ignored) {
            return false
        }
    }
}
