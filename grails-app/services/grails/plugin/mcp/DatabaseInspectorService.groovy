package grails.plugin.mcp

import groovy.transform.CompileDynamic
import groovy.sql.Sql

import javax.sql.DataSource

/**
 * DatabaseInspectorService
 * Database-agnostic JDBC service for SQL execution, schema inspection,
 * and automated data quality analysis.
 * Works with MySQL, PostgreSQL, Oracle, H2, and any JDBC datasource.
 */
@CompileDynamic
class DatabaseInspectorService {

    DataSource dataSource
    def grailsApplication

    private static final int MAX_ROWS = 500

    /**
     * Execute a raw SQL query.
     * SELECT only by default; set allowWrite=true for DML.
     */
    Map executeSql(String sql, boolean allowWrite = false, int maxRows = 100) {
        String trimmed = sql.trim().toUpperCase()
        boolean isWrite = trimmed.startsWith('INSERT') ||
                          trimmed.startsWith('UPDATE') ||
                          trimmed.startsWith('DELETE') ||
                          trimmed.startsWith('DROP')   ||
                          trimmed.startsWith('ALTER')  ||
                          trimmed.startsWith('TRUNCATE')

        if (isWrite && !allowWrite) {
            return [
                success: false,
                error  : 'Write SQL requires allowWrite=true and mcp:write scope.',
                errorType: 'PermissionDenied'
            ]
        }

        def rows = Math.min(maxRows, MAX_ROWS)
        long start = System.currentTimeMillis()

        try {
            def sqlClient = new Sql(dataSource)
            try {
                if (isWrite) {
                    int affected = sqlClient.executeUpdate(sql)
                    return [
                        success     : true,
                        rowsAffected: affected,
                        elapsedMs   : System.currentTimeMillis() - start
                    ]
                } else {
                    // Limit result set
                    String limitedSql = applyLimit(sql, rows)
                    def results = []
                    def columns = []

                    sqlClient.eachRow(limitedSql) { row ->
                        if (columns.isEmpty()) {
                            columns = row.getMetaData() ? (1..row.getMetaData().columnCount).collect {
                                row.getMetaData().getColumnName(it)
                            } : []
                        }
                        def rowMap = [:]
                        columns.each { col -> rowMap[col] = row[col] }
                        results << rowMap
                    }

                    return [
                        success  : true,
                        columns  : columns,
                        rows     : results,
                        rowCount : results.size(),
                        truncated: results.size() == rows,
                        elapsedMs: System.currentTimeMillis() - start
                    ]
                }
            } finally {
                sqlClient.close()
            }
        } catch (Exception e) {
            return [
                success  : false,
                error    : e.message,
                errorType: e.class.simpleName,
                elapsedMs: System.currentTimeMillis() - start
            ]
        }
    }

    /**
     * Get database schema — tables, columns, types, indexes, FKs.
     */
    Map getSchema(String tableName = null) {
        try {
            def conn = dataSource.connection
            try {
                def meta = conn.metaData
                def dbType = detectDbType(meta)
                def tables = []

                def tableRs = meta.getTables(null, getSchemaPattern(dbType), tableName ?: '%', ['TABLE'] as String[])
                while (tableRs.next()) {
                    def tName = tableRs.getString('TABLE_NAME')
                    def columns = []
                    def colRs = meta.getColumns(null, null, tName, '%')
                    while (colRs.next()) {
                        columns << [
                            name    : colRs.getString('COLUMN_NAME'),
                            type    : colRs.getString('TYPE_NAME'),
                            size    : colRs.getInt('COLUMN_SIZE'),
                            nullable: colRs.getString('IS_NULLABLE') == 'YES',
                            default : colRs.getString('COLUMN_DEF'),
                        ]
                    }
                    colRs.close()

                    // Indexes
                    def indexes = []
                    try {
                        def idxRs = meta.getIndexInfo(null, null, tName, false, true)
                        def idxMap = [:]
                        while (idxRs.next()) {
                            def idxName = idxRs.getString('INDEX_NAME')
                            if (idxName) {
                                idxMap.get(idxName, [name: idxName, unique: !idxRs.getBoolean('NON_UNIQUE'), columns: []])
                                    .columns << idxRs.getString('COLUMN_NAME')
                            }
                        }
                        idxRs.close()
                        indexes = idxMap.values().toList()
                    } catch (ignored) {}

                    // Foreign keys
                    def fks = []
                    try {
                        def fkRs = meta.getImportedKeys(null, null, tName)
                        while (fkRs.next()) {
                            fks << [
                                column          : fkRs.getString('FKCOLUMN_NAME'),
                                referencedTable : fkRs.getString('PKTABLE_NAME'),
                                referencedColumn: fkRs.getString('PKCOLUMN_NAME'),
                            ]
                        }
                        fkRs.close()
                    } catch (ignored) {}

                    tables << [name: tName, columns: columns, indexes: indexes, foreignKeys: fks]
                }
                tableRs.close()

                return [success: true, dbType: dbType, tables: tables, tableCount: tables.size()]
            } finally {
                conn.close()
            }
        } catch (Exception e) {
            return [success: false, error: e.message, errorType: e.class.simpleName]
        }
    }

    /**
     * Analyze the database for common data quality and performance issues.
     */
    Map analyzeIssues(String focus = 'all') {
        def issues = []
        def sqlClient = new Sql(dataSource)

        try {
            def dbType = detectDbType(dataSource.connection.metaData)

            // ── Integrity checks ─────────────────────────────────────────────
            if (focus in ['all', 'integrity']) {
                grailsApplication.domainClasses.each { dc ->
                    def tableName = toTableName(dc.name)

                    // Check for orphaned records (belongsTo without matching parent)
                    try {
                        def belongsTo = dc.clazz.belongsTo
                        if (belongsTo instanceof Map) {
                            belongsTo.each { propName, targetClass ->
                                def fkCol = "${propName}_id"
                                def parentTable = toTableName(targetClass.simpleName)
                                def orphanSql = """
                                    SELECT COUNT(*) as cnt FROM ${tableName} child
                                    LEFT JOIN ${parentTable} parent ON child.${fkCol} = parent.id
                                    WHERE child.${fkCol} IS NOT NULL AND parent.id IS NULL
                                """
                                try {
                                    def row = sqlClient.firstRow(orphanSql)
                                    if (row?.cnt > 0) {
                                        issues << [
                                            type   : 'ORPHANED_RECORDS',
                                            table  : tableName,
                                            detail : "${row.cnt} orphaned records in ${tableName}.${fkCol} (no matching ${parentTable})",
                                            severity: 'HIGH'
                                        ]
                                    }
                                } catch (ignored) {}
                            }
                        }
                    } catch (ignored) {}
                }
            }

            // ── Duplicate checks ─────────────────────────────────────────────
            if (focus in ['all', 'duplicates']) {
                grailsApplication.domainClasses.each { dc ->
                    try {
                        def constraints = dc.clazz.constraints
                        constraints?.each { propName, constraint ->
                            if (constraint.unique) {
                                def tableName = toTableName(dc.name)
                                def colName   = toColumnName(propName)
                                def dupSql = """
                                    SELECT ${colName}, COUNT(*) as cnt
                                    FROM ${tableName}
                                    WHERE ${colName} IS NOT NULL
                                    GROUP BY ${colName}
                                    HAVING COUNT(*) > 1
                                    LIMIT 5
                                """
                                try {
                                    def dups = sqlClient.rows(dupSql)
                                    if (dups) {
                                        issues << [
                                            type    : 'DUPLICATE_UNIQUE_VALUES',
                                            table   : tableName,
                                            column  : colName,
                                            detail  : "${dups.size()} duplicate value(s) found on unique field ${propName}. Sample: ${dups[0][colName]}",
                                            severity: 'HIGH'
                                        ]
                                    }
                                } catch (ignored) {}
                            }
                        }
                    } catch (ignored) {}
                }
            }

            // ── Performance checks ───────────────────────────────────────────
            if (focus in ['all', 'performance']) {
                grailsApplication.domainClasses.each { dc ->
                    def tableName = toTableName(dc.name)
                    try {
                        // Check table size
                        def countRow = sqlClient.firstRow("SELECT COUNT(*) as cnt FROM ${tableName}")
                        if (countRow?.cnt > 100000) {
                            issues << [
                                type    : 'LARGE_TABLE',
                                table   : tableName,
                                detail  : "${countRow.cnt} rows — ensure proper indexes exist on frequently queried columns",
                                severity: 'MEDIUM'
                            ]
                        }

                        // Check for missing index on common FK columns
                        def schemaInfo = getSchema(tableName)
                        schemaInfo.tables?.each { tbl ->
                            def indexedCols = tbl.indexes?.collectMany { it.columns } ?: []
                            tbl.foreignKeys?.each { fk ->
                                if (!(fk.column in indexedCols)) {
                                    issues << [
                                        type    : 'MISSING_FK_INDEX',
                                        table   : tableName,
                                        column  : fk.column,
                                        detail  : "Foreign key column ${fk.column} has no index — can cause slow JOINs",
                                        severity: 'MEDIUM'
                                    ]
                                }
                            }
                        }
                    } catch (ignored) {}
                }
            }

        } finally {
            sqlClient.close()
        }

        def grouped = issues.groupBy { it.severity }
        return [
            success    : true,
            totalIssues: issues.size(),
            high       : grouped['HIGH']?.size()   ?: 0,
            medium     : grouped['MEDIUM']?.size()  ?: 0,
            low        : grouped['LOW']?.size()     ?: 0,
            issues     : issues
        ]
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String detectDbType(def meta) {
        try { meta.databaseProductName?.toLowerCase() ?: 'unknown' } catch (e) { 'unknown' }
    }

    private String getSchemaPattern(String dbType) {
        if (dbType.contains('postgres')) return 'public'
        if (dbType.contains('oracle'))   return System.getProperty('user.name')?.toUpperCase()
        return null
    }

    private String applyLimit(String sql, int limit) {
        // Don't double-wrap if already has LIMIT
        if (sql.toUpperCase().contains('LIMIT')) return sql
        return "SELECT * FROM (${sql}) mcp_wrap_ LIMIT ${limit}"
    }

    private String toTableName(String className) {
        // Grails default: CamelCase → camel_case
        className.replaceAll(/([A-Z])/, /_$1/).toLowerCase().replaceFirst(/^_/, '')
    }

    private String toColumnName(String propName) {
        propName.replaceAll(/([A-Z])/, /_$1/).toLowerCase()
    }
}
