package grails.plugin.mcp

import groovy.transform.CompileDynamic
import org.springframework.context.ApplicationContext

import javax.sql.DataSource
import java.sql.Connection

/**
 * AppInspectorService
 * Exposes resolved runtime config (with sensitive values redacted),
 * Spring beans, and application health metrics.
 */
@CompileDynamic
class AppInspectorService {

    ApplicationContext applicationContext
    def grailsApplication
    DataSource dataSource

    // Patterns that trigger value redaction
    private static final List<String> REDACT_PATTERNS = [
        'password', 'secret', 'key', 'token', 'credential',
        'private', 'apikey', 'api_key', 'auth', 'jwt'
    ]

    /**
     * Return resolved app config with sensitive values masked.
     */
    Map getConfig(String prefix = '') {
        def config = grailsApplication.config
        def flat   = flattenConfig(config.toProperties(), prefix)
        return [
            success    : true,
            environment: grailsApplication.metadata.environment ?: 'unknown',
            prefix     : prefix ?: '(all)',
            properties : flat
        ]
    }

    /**
     * List Spring beans, optionally filtered by name or type.
     */
    Map getBeans(String nameFilter = '', String typeFilter = '') {
        def names = applicationContext.beanDefinitionNames.toList().sort()

        if (nameFilter) {
            names = names.findAll { it.toLowerCase().contains(nameFilter.toLowerCase()) }
        }

        def beans = names.collect { beanName ->
            def info = [name: beanName]
            try {
                def bean = applicationContext.getBean(beanName)
                info.type = bean.class.name
                if (typeFilter && !info.type.toLowerCase().contains(typeFilter.toLowerCase())) {
                    return null
                }
            } catch (ignored) {
                info.type = '(not instantiated)'
            }
            info
        }.findAll { it != null }

        return [success: true, total: beans.size(), beans: beans]
    }

    /**
     * Return app health: memory, threads, DB pool, version info.
     */
    Map getHealth() {
        def runtime = Runtime.runtime
        def memUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        def memTotal = runtime.totalMemory() / 1024 / 1024
        def memMax   = runtime.maxMemory()   / 1024 / 1024

        def health = [
            success      : true,
            status       : 'UP',
            grailsVersion: grailsApplication.metadata.grailsVersion,
            grailsAppName: grailsApplication.metadata.applicationName,
            javaVersion  : System.getProperty('java.version'),
            javaVendor   : System.getProperty('java.vendor'),
            os           : "${System.getProperty('os.name')} ${System.getProperty('os.version')}",
            memory       : [
                usedMb : Math.round(memUsed),
                totalMb: Math.round(memTotal),
                maxMb  : Math.round(memMax),
                usedPct: Math.round(memUsed / memMax * 100)
            ],
            threads      : Thread.activeCount(),
            processors   : runtime.availableProcessors(),
        ]

        // Database connectivity check
        try {
            Connection conn = dataSource.connection
            def meta = conn.metaData
            health.database = [
                status : 'UP',
                product: meta.databaseProductName,
                version: meta.databaseProductVersion,
                url    : sanitizeJdbcUrl(meta.URL),
            ]
            conn.close()
        } catch (Exception e) {
            health.database = [status: 'DOWN', error: e.message]
            health.status   = 'DEGRADED'
        }

        return health
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Map flattenConfig(Properties props, String prefix) {
        def result = [:]
        props.each { key, value ->
            String k = key.toString()
            if (prefix && !k.startsWith(prefix)) return
            String v = shouldRedact(k) ? '***REDACTED***' : value?.toString()
            result[k] = v
        }
        result.sort { it.key }
    }

    private boolean shouldRedact(String key) {
        String lower = key.toLowerCase()
        REDACT_PATTERNS.any { lower.contains(it) }
    }

    private String sanitizeJdbcUrl(String url) {
        // Remove password from JDBC URL if present
        url?.replaceAll(/password=[^&;]+/, 'password=***') ?: 'unknown'
    }
}
