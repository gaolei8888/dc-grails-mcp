package grails.plugin.mcp

import groovy.transform.CompileDynamic

/**
 * DomainInspectorService
 * Introspects all registered GORM domain classes, their properties,
 * constraints, and relationships.
 */
@CompileDynamic
class DomainInspectorService {

    def grailsApplication

    /**
     * Returns full domain model. If className is given, returns just that class.
     */
    Map getDomainModel(String className = null) {
        def domainClasses = grailsApplication.domainClasses

        if (className) {
            def dc = domainClasses.find { it.name == className || it.fullName == className }
            if (!dc) return [error: "Domain class not found: ${className}"]
            return [domainClasses: [inspectClass(dc)]]
        }

        return [
            total       : domainClasses.size(),
            domainClasses: domainClasses.sort { it.name }.collect { inspectClass(it) }
        ]
    }

    /**
     * Returns a relationship graph across all domain classes.
     */
    Map getRelationships() {
        def relationships = [:]

        grailsApplication.domainClasses.each { dc ->
            def rels = []

            // hasMany
            dc.clazz.metaClass.properties.find { it.name == 'hasMany' }?.with {
                def hasMany = dc.clazz.hasMany
                hasMany?.each { propName, targetClass ->
                    rels << [type: 'hasMany', propertyName: propName, target: targetClass.simpleName]
                }
            }

            // belongsTo
            try {
                def belongsTo = dc.clazz.belongsTo
                if (belongsTo instanceof Map) {
                    belongsTo.each { propName, targetClass ->
                        rels << [type: 'belongsTo', propertyName: propName, target: targetClass.simpleName]
                    }
                } else if (belongsTo instanceof Class) {
                    rels << [type: 'belongsTo', propertyName: belongsTo.simpleName.uncapitalize(), target: belongsTo.simpleName]
                }
            } catch (ignored) {}

            // hasOne
            try {
                def hasOne = dc.clazz.hasOne
                hasOne?.each { propName, targetClass ->
                    rels << [type: 'hasOne', propertyName: propName, target: targetClass.simpleName]
                }
            } catch (ignored) {}

            if (rels) relationships[dc.name] = rels
        }

        return [relationships: relationships]
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Map inspectClass(dc) {
        def props = dc.persistentProperties.collect { prop ->
            def info = [
                name    : prop.name,
                type    : prop.type?.simpleName ?: 'unknown',
                nullable: true,
                blank   : true,
            ]
            // Constraints
            try {
                def constraints = dc.clazz.constraints
                def c = constraints?[prop.name]
                if (c) {
                    info.nullable = c.nullable
                    info.blank    = c.blank
                    if (c.maxSize)  info.maxSize  = c.maxSize
                    if (c.minSize)  info.minSize  = c.minSize
                    if (c.inList)   info.inList   = c.inList
                    if (c.matches)  info.matches  = c.matches
                    if (c.unique)   info.unique   = c.unique
                    if (c.email)    info.email    = c.email
                    if (c.url)      info.url      = c.url
                }
            } catch (ignored) {}
            info
        }

        // Transient properties
        def transients = []
        try { transients = dc.clazz.transients ?: [] } catch (ignored) {}

        // GORM mapping info
        def mappingInfo = [:]
        try {
            def mapping = dc.clazz.mapping
            if (mapping) {
                // Just capture table name if overridden
                mappingInfo.hasCustomMapping = true
            }
        } catch (ignored) {}

        return [
            name            : dc.name,
            fullName        : dc.fullName,
            properties      : props,
            transients      : transients,
            hasMany         : safeGet { dc.clazz.hasMany?.keySet()?.toList() } ?: [],
            belongsTo       : safeGet { dc.clazz.belongsTo instanceof Map ? dc.clazz.belongsTo.keySet().toList() : (dc.clazz.belongsTo ? [dc.clazz.belongsTo.simpleName] : []) } ?: [],
            hasOne          : safeGet { dc.clazz.hasOne?.keySet()?.toList() } ?: [],
            propertyCount   : props.size(),
        ]
    }

    private Object safeGet(Closure c) {
        try { c() } catch (e) { null }
    }
}
