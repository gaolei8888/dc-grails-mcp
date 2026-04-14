package grails.plugin.mcp.tools

import grails.plugin.mcp.LogReaderService
import groovy.transform.CompileDynamic
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileDynamic
class LogTools {

    @Autowired
    LogReaderService logReaderService

    @Tool(name = "gr_logs",
             description = """Read recent application log lines with optional filtering.
Reads from the configured log file (grails.mcp.logFile) or common Grails log locations.
Supports filtering by log level (respects level hierarchy — e.g. WARN includes WARN and ERROR) and by substring pattern.""")
    String getLogs(
            @ToolParam(description = "Number of log lines to return (default 100)")
            Integer lines,
            @ToolParam(description = "Filter by minimum log level: ERROR, WARN, INFO, DEBUG, TRACE")
            String level,
            @ToolParam(description = "Filter by substring pattern")
            String pattern) {

        int numLines = lines ?: 100
        def result = logReaderService.getLogs(numLines, level ?: '', pattern ?: '')
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }

    @Tool(name = "gr_exceptions",
             description = """Get recent exceptions from the application log, grouped by type and message.
Scans ERROR-level log lines, extracts exception class names and messages, groups by type, counts occurrences, and tracks first/last occurrence timestamps. Returns up to 20 exception types sorted by frequency.""")
    String getRecentExceptions(
            @ToolParam(description = "Look for exceptions within the last N minutes (default 60)")
            Integer sinceMinutes) {

        int minutes = sinceMinutes ?: 60
        def result = logReaderService.getRecentExceptions(minutes)
        return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }
}
