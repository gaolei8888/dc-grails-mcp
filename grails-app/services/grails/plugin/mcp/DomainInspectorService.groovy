package grails.plugin.mcp

import groovy.transform.CompileDynamic
import org.springframework.context.ApplicationContext

/**
 * DomainInspectorService
 * Introspects all registered GORM domain classes, their properties,
 * constraints, and relationships.
 *
 * Compatible with Grails 7 / GORM 9 — uses GrailsDomainClass API with
 * fallback to reflection for properties when persistentProperties is unavailable.
 */
@CompileDynamic
class DomainInspectorService {

    def grailsApplication
    ApplicationContext applicationContext

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
            try {
                def hasMany = dc.clazz.hasMany
                hasMany?.each { propName, targetClass ->
                    rels << [type: 'hasMany', propertyName: propName, target: targetClass.simpleName]
                }
            } catch (ignored) {}

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
        def props = getProperties(dc)

        // Transient properties
        def transients = []
        try { transients = dc.clazz.transients ?: [] } catch (ignored) {}

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

    /**
     * Get properties from a domain class, handling both old and new GORM APIs.
     */
    private List<Map> getProperties(dc) {
        // Try persistentProperties first (works in some GORM versions)
        try {
            if (dc.respondsTo('getPersistentProperties') || dc.metaClass.respondsTo(dc, 'getPersistentProperties')) {
                def persistentProps = dc.persistentProperties
                if (persistentProps != null) {
                    return persistentProps.collect { prop -> buildPropertyInfo(dc, prop.name, prop.type?.simpleName ?: 'unknown') }
                }
            }
        } catch (ignored) {}

        // Try GORM MappingContext (Grails 7 / GORM 9)
        try {
            def mappingContext = applicationContext.getBean('grailsDomainClassMappingContext')
            def entity = mappingContext.getPersistentEntity(dc.fullName)
            if (entity) {
                def allProps = []
                entity.persistentProperties.each { prop ->
                    allProps << buildPropertyInfo(dc, prop.name, prop.type?.simpleName ?: 'unknown')
                }
                return allProps
            }
        } catch (ignored) {}

        // Fallback: use declared fields, exclude internal Grails fields
        def excludeFields = ['id', 'version', 'errors', 'attached', 'dirty', 'dirtyPropertyNames',
                             'properties', 'class', 'constraints', 'mapping', 'hasMany', 'belongsTo',
                             'hasOne', 'transients', 'metaClass', 'log', '__timeStamp',
                             '__hashCodeCalc', '__equalsCalc'] as Set

        def transients = safeGet { dc.clazz.transients ?: [] } ?: []

        return dc.clazz.declaredFields
            .findAll { f -> !f.synthetic && !java.lang.reflect.Modifier.isStatic(f.modifiers) && !(f.name in excludeFields) && !(f.name in transients) }
            .collect { f -> buildPropertyInfo(dc, f.name, f.type?.simpleName ?: 'unknown') }
    }

    /**
     * Build property info map with constraint details.
     */
    private Map buildPropertyInfo(dc, String propName, String typeName) {
        def info = [
            name    : propName,
            type    : typeName,
            nullable: true,
            blank   : true,
        ]
        try {
            def constraints = dc.clazz.constraints
            def c = constraints?[propName]
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

    private Object safeGet(Closure c) {
        try { c() } catch (e) { null }
    }
}
