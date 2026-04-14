package grails.plugin.mcp.tools

import grails.plugin.mcp.DatabaseInspectorService
import grails.plugin.mcp.McpAuditService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileDynamic
class DatabaseTools {

    @Autowired
    DatabaseInspectorService databaseInspectorService

    @Autowired
    McpAuditService mcpAuditService

    @Tool(name = "execute_sql",
             description = """Execute a raw SQL query against the application database via JDBC.
SELECT queries run by default. For INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE, set allow_write=true.
Results are capped at max_rows (hard limit: 500).
Returns columns, rows, rowCount for SELECT; rowsAffected for DML.""")
    String executeSql(
            @ToolParam(description = "SQL query to execute", required = true)
            String sql,
            @ToolParam(description = "Set true to allow INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE")
            Boolean allowWrite,
            @ToolParam(description = "Maximum rows to return for SELECT queries (default 100, hard cap 500)")
            Integer maxRows) {

        boolean isWrite = allowWrite ?: false
        int rows = maxRows ?: 100
        mcpAuditService.log('mcp-client', 'execute_sql', [sql: sql, allowWrite: isWrite])
        def result = databaseInspectorService.executeSql(sql, isWrite, rows)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "get_database_schema",
             description = """Get the actual database schema from JDBC metadata.
Returns tables with: column names/types/sizes/nullability/defaults, indexes (with uniqueness and column lists), and foreign keys (with referenced table/column).
Works with MySQL, PostgreSQL, Oracle, H2, and any JDBC datasource.""")
    String getDatabaseSchema(
            @ToolParam(description = "Optional: filter to a single table name")
            String table) {

        def result = databaseInspectorService.getSchema(table ?: null)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "analyze_database_issues",
             description = """Automated data quality and performance scanner.
Checks for:
- integrity: orphaned records (belongsTo FK with no parent row)
- duplicates: values that violate unique constraints
- performance: tables >100K rows, foreign key columns missing indexes
Issues are classified as HIGH/MEDIUM/LOW severity.""")
    String analyzeDatabaseIssues(
            @ToolParam(description = "Which analysis categories to run: 'all', 'integrity', 'duplicates', or 'performance' (default: all)")
            String focus) {

        def result = databaseInspectorService.analyzeIssues(focus ?: 'all')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
