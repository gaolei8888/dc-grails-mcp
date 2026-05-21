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
            } catch (Exception ignored) {
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
                }
            } catch (Exception ignored) {}
        }

        if (toolBeans.isEmpty()) {
            println "Grails MCP Plugin: No @McpTool beans found, skipping registration"
            return
        }

        // Convert @McpTool annotated methods to ToolCallbacks
        ToolCallback[] callbacks
        try {
            callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build()
                .getToolCallbacks()
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

        def allTools = (pluginTools + hostTools).join(', ')
        def line = '-' * 50
        println ''
        println line
        println "  Grails MCP Plugin  |  ${endpoint}  |  ${callbacks.length} tools"
        println "  ${allTools}"
        println line
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
