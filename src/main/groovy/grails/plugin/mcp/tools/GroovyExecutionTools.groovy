package grails.plugin.mcp.tools

import grails.plugin.mcp.GroovyExecutorService
import grails.plugin.mcp.McpAuditService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileDynamic
class GroovyExecutionTools {

    @Autowired
    GroovyExecutorService groovyExecutorService

    @Autowired
    McpAuditService mcpAuditService

    @Tool(name = "gr_groovy",
             description = """Execute a Groovy script in the live Grails application context.
All domain classes are pre-injected by short name (e.g. User, Order).
All services are pre-injected by property name (e.g. userService).
The Spring ApplicationContext is available as 'ctx'.
The grailsApplication object is available.
Scripts run in a sandbox with a configurable timeout (default 30s).
Output is capped at 512KB. Non-transactional (read-safe).""")
    String executeGroovy(
            @ToolParam(description = "Groovy script to execute. Examples: User.count(), Order.findAllByStatus('PENDING', [max: 10]), ctx.getBean('myService').doSomething()", required = true)
            String script) {

        mcpAuditService.log('mcp-client', 'execute_groovy', [script: script])
        def result = groovyExecutorService.execute(script)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "gr_groovy_tx",
             description = """Execute a Groovy script inside a database transaction.
Same context as execute_groovy but wrapped in a DB transaction.
Set dry_run=true to execute then rollback — safe preview of data changes.
Use this for INSERT/UPDATE/DELETE operations via GORM.""")
    String executeGroovyTransaction(
            @ToolParam(description = "Groovy script to execute inside a DB transaction", required = true)
            String script,
            @ToolParam(description = "If true, transaction is rolled back after execution (safe preview of changes)")
            Boolean dryRun) {

        boolean isDryRun = dryRun ?: false
        mcpAuditService.log('mcp-client', 'execute_groovy_transaction', [script: script, dryRun: isDryRun])
        def result = groovyExecutorService.executeTransaction(script, isDryRun)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
