package grails.plugin.mcp

import groovy.transform.CompileDynamic

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * LogReaderService
 * Reads application logs from the configured log file.
 * Supports filtering by level and pattern, and exception grouping.
 */
@CompileDynamic
class LogReaderService {

    def grailsApplication

    private static final List<String> LOG_LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
    private static final int MAX_LINES = 2000

    /**
     * Read recent log lines with optional filtering.
     */
    Map getLogs(int lines = 100, String level = '', String pattern = '') {
        def logFile = resolveLogFile()
        if (!logFile) {
            return [
                success: false,
                error  : 'Log file not found. Set grails.mcp.logFile in application.yml.',
                logs   : []
            ]
        }

        try {
            def allLines = readTailLines(logFile, Math.min(lines * 5, MAX_LINES))

            // Filter by level
            if (level) {
                allLines = allLines.findAll { line ->
                    LOG_LEVELS.dropWhile { it != level.toUpperCase() }
                        .any { lvl -> line.contains(" ${lvl} ") || line.contains("[${lvl}]") }
                }
            }

            // Filter by pattern
            if (pattern) {
                allLines = allLines.findAll { it.contains(pattern) }
            }

            // Take the last N matching lines
            def result = allLines.size() > lines ? allLines[-lines..-1] : allLines

            return [
                success  : true,
                logFile  : logFile.path,
                lineCount: result.size(),
                logs     : result
            ]
        } catch (Exception e) {
            return [success: false, error: e.message, logs: []]
        }
    }

    /**
     * Get recent exceptions grouped by type and message.
     */
    Map getRecentExceptions(int sinceMinutes = 60) {
        def logFile = resolveLogFile()
        if (!logFile) {
            return [success: false, error: 'Log file not configured.', exceptions: []]
        }

        try {
            def allLines = readTailLines(logFile, MAX_LINES)
            def errorLines = allLines.findAll { it.contains(' ERROR ') || it.contains('[ERROR]') }

            // Group exceptions
            def exceptionMap = [:]
            def currentException = null

            errorLines.each { line ->
                // Detect exception lines: "Caused by:" or "at com.example..."
                if (line =~ /Exception|Error:/) {
                    def matcher = (line =~ /(\w+(?:Exception|Error))[:\s](.*)/)
                    if (matcher.find()) {
                        def key = matcher.group(1) + ':' + matcher.group(2).take(100)
                        if (!exceptionMap[key]) {
                            exceptionMap[key] = [
                                type           : matcher.group(1),
                                message        : matcher.group(2).take(200),
                                count          : 0,
                                firstOccurrence: extractTimestamp(line),
                                lastOccurrence : extractTimestamp(line),
                                stackSample    : line,
                            ]
                        }
                        exceptionMap[key].count++
                        exceptionMap[key].lastOccurrence = extractTimestamp(line)
                    }
                }
            }

            def exceptions = exceptionMap.values()
                .sort { -it.count }
                .take(20)

            return [
                success         : true,
                sinceMinutes    : sinceMinutes,
                totalExceptions : exceptions.sum { it.count } ?: 0,
                uniqueTypes     : exceptions.size(),
                exceptions      : exceptions.toList()
            ]
        } catch (Exception e) {
            return [success: false, error: e.message, exceptions: []]
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private File resolveLogFile() {
        // 1. Explicit config
        String configured = grailsApplication.config
            .getProperty('grails.mcp.logFile', String, '')
        if (configured) {
            def f = new File(configured)
            if (f.exists()) return f
        }

        // 2. Common Grails log locations
        def candidates = [
            "logs/${grailsApplication.metadata.applicationName}.log",
            'logs/application.log',
            '/var/log/app/application.log',
            "build/logs/${grailsApplication.metadata.applicationName}.log",
        ]
        return candidates.collect { new File(it) }.find { it.exists() }
    }

    private List<String> readTailLines(File file, int maxLines) {
        // Reverse-read from end of file using RandomAccessFile to avoid OOM on large files.
        // Reads in chunks and decodes as UTF-8 for correctness with multi-byte characters.
        long fileLength = file.length()
        if (fileLength == 0) return []

        int chunkSize = 8192
        def raf = new RandomAccessFile(file, 'r')
        try {
            long readEnd = fileLength
            ByteArrayOutputStream buffer = new ByteArrayOutputStream()

            while (readEnd > 0) {
                long readStart = Math.max(readEnd - chunkSize, 0)
                int bytesToRead = (int) (readEnd - readStart)
                raf.seek(readStart)
                byte[] chunk = new byte[bytesToRead]
                raf.readFully(chunk)

                // Prepend chunk to buffer
                ByteArrayOutputStream newBuffer = new ByteArrayOutputStream()
                newBuffer.write(chunk)
                newBuffer.write(buffer.toByteArray())
                buffer = newBuffer

                // Count newlines to see if we have enough lines
                int lineCount = 0
                byte[] accumulated = buffer.toByteArray()
                for (int i = 0; i < accumulated.length; i++) {
                    if (accumulated[i] == (byte) '\n') lineCount++
                }
                // We need maxLines newlines (plus possible partial first line)
                if (lineCount >= maxLines + 1) break

                readEnd = readStart
            }

            // Decode the accumulated bytes as UTF-8 and split into lines
            String text = new String(buffer.toByteArray(), 'UTF-8')
            def lines = text.split('\n', -1) as List<String>

            // Remove trailing empty element from trailing newline
            if (lines && lines[-1] == '') {
                lines = lines[0..-2]
            }

            // Return only the last maxLines
            return lines.size() > maxLines ? lines[-maxLines..-1] : lines
        } finally {
            raf.close()
        }
    }

    private String extractTimestamp(String line) {
        // Try to extract timestamp from common log formats
        def matcher = (line =~ /(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})/)
        matcher.find() ? matcher.group(1) : ''
    }
}
