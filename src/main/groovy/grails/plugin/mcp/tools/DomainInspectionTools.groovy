package grails.plugin.mcp.tools

import grails.plugin.mcp.DomainInspectorService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileDynamic
class DomainInspectionTools {

    @Autowired
    DomainInspectorService domainInspectorService

    @Tool(name = "get_domain_model",
             description = """Get GORM domain class definitions including properties, types, constraints, and relationships.
Returns: class name, full name, persistent properties (name, type, nullable, blank, maxSize, minSize, inList, matches, unique, email, url), transient properties, hasMany/belongsTo/hasOne relationships, property count.
Without className parameter, returns ALL domain classes.""")
    String getDomainModel(
            @ToolParam(description = "Optional: filter to a single domain class by simple or full name (e.g. 'User' or 'com.dc.User')")
            String className) {

        def result = domainInspectorService.getDomainModel(className ?: null)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "get_domain_relationships",
             description = """Get the full relationship graph across all GORM domain classes.
Returns hasMany, belongsTo, and hasOne relationships for every domain class, showing property names and target types. Useful for understanding the data model.""")
    String domainRelationships() {
        def result = domainInspectorService.getRelationships()
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
