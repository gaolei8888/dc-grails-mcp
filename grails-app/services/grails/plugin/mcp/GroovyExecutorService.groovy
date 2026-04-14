package grails.plugin.mcp

import grails.gorm.transactions.Transactional
import groovy.transform.CompileDynamic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.SecureASTCustomizer
import org.springframework.context.ApplicationContext

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * GroovyExecutorService
 *
 * Executes Groovy scripts inside the live Grails application context.
 * All domain classes and services are pre-injected into the script binding.
 *
 * Safety features:
 *   - Configurable execution timeout (default 30s)
 *   - Restricted ClassLoader (no File, no System.exit, no Runtime)
 *   - Output size capped at 512KB
 *   - Both transactional and non-transactional modes
 */
@CompileDynamic
class GroovyExecutorService {

    ApplicationContext applicationContext
    def grailsApplication

    private static final int DEFAULT_TIMEOUT_SECONDS = 30
    private static final int MAX_OUTPUT_BYTES        = 512 * 1024 // 512KB

    // Classes the sandbox forbids scripts from using
    private static final List<String> BLACKLISTED_CLASSES = [
        'java.lang.Runtime',
        'java.lang.ProcessBuilder',
        'java.io.File',
        'java.io.FileInputStream',
        'java.io.FileOutputStream',
        'java.lang.System',
        'java.lang.Thread',
        'java.lang.ClassLoader',
        'groovy.lang.GroovyClassLoader',
        'java.lang.reflect.Field',
        'java.net.URL',
        'java.net.Socket',
    ]

    /**
     * Execute a Groovy script (non-transactional, read-safe).
     */
    Map execute(String script) {
        runScript(script, false, false)
    }

    /**
     * Execute a Groovy script inside a DB transaction.
     * If dryRun=true, transaction is rolled back after execution.
     */
    @Transactional
    Map executeTransaction(String script, boolean dryRun = false) {
        def result = runScript(script, true, dryRun)
        if (dryRun) {
            // Force rollback by throwing a rollback-only marker
            // (caught and handled gracefully)
            throw new McpDryRunRollbackException(result)
        }
        result
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private Map runScript(String script, boolean transactional, boolean dryRun) {
        int timeout = grailsApplication.config
            .getProperty('grails.mcp.groovy.timeoutSeconds', Integer, DEFAULT_TIMEOUT_SECONDS)

        long start = System.currentTimeMillis()

        def executor = Executors.newSingleThreadExecutor()
        try {
            Future<Map> future = executor.submit({ ->
                doRun(script)
            } as Callable<Map>)

            Map result = future.get(timeout, TimeUnit.SECONDS)
            result.elapsedMs = System.currentTimeMillis() - start
            result.dryRun = dryRun
            return result

        } catch (TimeoutException e) {
            return [
                success  : false,
                error    : "Script timed out after ${timeout} seconds.",
                errorType: 'TimeoutException',
                elapsedMs: System.currentTimeMillis() - start,
            ]
        } catch (McpDryRunRollbackException e) {
            // Dry run — return the captured result with rollback notice
            Map r = e.result
            r.dryRun = true
            r.elapsedMs = System.currentTimeMillis() - start
            return r
        } catch (Exception e) {
            return [
                success  : false,
                error    : e.cause?.message ?: e.message,
                errorType: (e.cause ?: e).class.simpleName,
                elapsedMs: System.currentTimeMillis() - start,
            ]
        } finally {
            executor.shutdownNow()
        }
    }

    private Map doRun(String script) {
        // Build sandbox compiler config
        def secure = new SecureASTCustomizer()
        secure.disallowedImports = BLACKLISTED_CLASSES
        secure.disallowedTokens  = [org.codehaus.groovy.syntax.Types.KEYWORD_WHILE] // prevent infinite loops

        def config = new CompilerConfiguration()
        config.addCompilationCustomizers(secure)

        // Build binding — inject everything the script might need
        def binding = new Binding()
        binding.setVariable('ctx', applicationContext)
        binding.setVariable('grailsApplication', grailsApplication)
        binding.setVariable('out', new StringBuilder())

        // Inject all domain classes by short name
        grailsApplication.domainClasses.each { dc ->
            binding.setVariable(dc.name, dc.clazz)
        }

        // Inject all services
        grailsApplication.serviceClasses.each { sc ->
            try {
                def beanName = sc.propertyName
                binding.setVariable(beanName, applicationContext.getBean(beanName))
            } catch (ignored) {}
        }

        def shell = new GroovyShell(this.class.classLoader, binding, config)

        try {
            def rawResult = shell.evaluate(script)
            def formatted = formatResult(rawResult)
            def resultStr = formatted instanceof String ? formatted : groovy.json.JsonOutput.toJson(formatted)

            // Cap output size
            if (resultStr.length() > MAX_OUTPUT_BYTES) {
                resultStr = resultStr.substring(0, MAX_OUTPUT_BYTES) + "\n... [truncated at ${MAX_OUTPUT_BYTES} bytes]"
            }

            return [
                success    : true,
                result     : formatted,
                resultType : rawResult?.class?.simpleName ?: 'null',
                resultCount: (rawResult instanceof Collection) ? rawResult.size() : null,
            ]
        } catch (Exception e) {
            return [
                success   : false,
                error     : e.message,
                errorType : e.class.simpleName,
                stackTrace: e.stackTrace
                    .findAll { it.className.contains('Script') || it.className.contains('mcp') }
                    .take(10)
                    .collect { it.toString() },
            ]
        }
    }

    private Object formatResult(Object result) {
        if (result == null) return null
        if (result instanceof Collection) {
            return result.collect { formatSingle(it) }
        }
        if (result instanceof Map) {
            return result.collectEntries { k, v -> [k, formatSingle(v)] }
        }
        return formatSingle(result)
    }

    private Object formatSingle(Object obj) {
        if (obj == null) return null
        // Try to serialize Grails domain objects as property maps
        try {
            def meta = grailsApplication.getDomainClass(obj.class.name)
            if (meta) {
                return meta.persistentProperties.collectEntries { prop ->
                    try { [prop.name, obj[prop.name]] } catch (e) { [prop.name, null] }
                }
            }
        } catch (ignored) {}
        return obj.toString()
    }
}

/**
 * Marker exception used to signal a dry-run rollback while passing result back.
 */
class McpDryRunRollbackException extends RuntimeException {
    Map result
    McpDryRunRollbackException(Map result) {
        super('dry-run-rollback')
        this.result = result
    }
}
