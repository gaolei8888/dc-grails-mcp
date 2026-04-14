# dc-grails-mcp

A Grails 7 plugin that adds a native MCP (Model Context Protocol) server to any Grails application, allowing Claude Code to connect directly via Streamable HTTP. No JWT, no Python bridge -- just direct MCP protocol over HTTP, powered by Spring AI MCP Server WebMVC.

## Architecture

```
Claude Code
    |
    | Streamable HTTP
    v
 /mcp endpoint  (Spring AI MCP Server WebMVC)
    |
    v
 McpToolRegistrar  (auto-discovers @McpTool beans at ContextRefreshedEvent)
    |
    +---> GroovyExecutionTools  ---> GroovyExecutorService   (sandboxed Groovy REPL)
    +---> DomainInspectionTools ---> DomainInspectorService   (GORM introspection)
    +---> DatabaseTools         ---> DatabaseInspectorService  (JDBC operations)
    +---> LogTools              ---> LogReaderService           (log file reading)
    +---> AppInspectionTools    ---> AppInspectorService        (config/beans/health)
    +---> [Host App Tools]      ---> [Host App Services]       (auto-discovered)
    |
    v
 McpAuditService  (audit logging for all tool invocations)
```

## Built-in Tools

The plugin ships with 12 tools across 5 categories:

### Groovy Execution

| Tool | Description | Parameters |
|------|-------------|------------|
| `execute_groovy` | Execute a Groovy script in the live Grails context. All domain classes are pre-injected by short name (e.g. `User`), all services by property name (e.g. `userService`), and the Spring `ApplicationContext` is available as `ctx`. Non-transactional (read-safe). | `script` (required): Groovy script to execute |
| `execute_groovy_transaction` | Execute a Groovy script inside a DB transaction. Set `dry_run=true` to execute then rollback -- safe preview of data changes. | `script` (required): Groovy script; `dryRun`: rollback after execution if true |

### Domain Model Inspection

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_domain_model` | Get GORM domain class definitions including properties, types, constraints (nullable, blank, maxSize, minSize, inList, matches, unique, email, url), transients, and relationships. Without `className`, returns all domain classes. | `className`: filter by simple or full class name |
| `get_domain_relationships` | Get the full relationship graph across all GORM domain classes -- hasMany, belongsTo, and hasOne for every class. | (none) |

### Database

| Tool | Description | Parameters |
|------|-------------|------------|
| `execute_sql` | Execute raw SQL against the application database via JDBC. SELECT only by default; write operations require `allowWrite=true`. Results capped at `maxRows`. | `sql` (required): SQL query; `allowWrite`: enable DML; `maxRows`: row limit (default 100, hard cap 500) |
| `get_database_schema` | Get the actual database schema from JDBC metadata -- tables, columns, types, sizes, nullability, defaults, indexes, and foreign keys. Works with MySQL, PostgreSQL, Oracle, H2, and any JDBC datasource. | `table`: filter to a single table |
| `analyze_database_issues` | Automated data quality and performance scanner. Checks for orphaned records, duplicate unique values, large tables (>100K rows), and missing FK indexes. Issues classified as HIGH/MEDIUM/LOW severity. | `focus`: `all`, `integrity`, `duplicates`, or `performance` |

### Logs

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_logs` | Read recent application log lines with optional filtering by level (respects hierarchy -- WARN includes WARN and ERROR) and substring pattern. | `lines`: number of lines (default 100); `level`: ERROR, WARN, INFO, DEBUG, TRACE; `pattern`: substring filter |
| `get_recent_exceptions` | Get recent exceptions from the log, grouped by type and message. Returns up to 20 exception types sorted by frequency with first/last occurrence timestamps. | `sinceMinutes`: lookback window (default 60) |

### Application Inspection

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_app_config` | Get the resolved runtime configuration. Sensitive values (passwords, secrets, keys, tokens, credentials) are automatically redacted. | `prefix`: filter by config key prefix (e.g. `spring.datasource`) |
| `get_spring_beans` | List all Spring beans in the ApplicationContext with name and type. | `filter`: name substring; `typeFilter`: type substring |
| `get_app_health` | Get application health: Grails/Java/OS versions, memory usage (MB and %), thread count, processor count, and database connectivity status. | (none) |

## Quick Start

### 1. Build the plugin

```bash
cd dc-grails-mcp
./gradlew publishToMavenLocal
```

### 2. Add the dependency to your host app

In your host app's `build.gradle`, add `mavenLocal()` to repositories and the plugin as a dependency:

```groovy
repositories {
    mavenLocal()
    // ... your other repositories
}

dependencies {
    implementation group: 'com.dc', name: 'dc-grails-mcp', version: '0.0.1'
}
```

### 3. Add MCP configuration to application.yml

Add the following to your host app's `grails-app/conf/application.yml`:

```yaml
spring:
    ai:
        mcp:
            server:
                enabled: true
                name: grails-mcp-server
                version: 0.0.1
                protocol: STREAMABLE
                type: SYNC
                instructions: "Grails application MCP server with domain introspection, Groovy execution, database access, and log reading."
                annotation-scanner:
                    enabled: true
                streamable-http:
                    mcp-endpoint: /mcp

grails:
    mcp:
        groovy:
            timeoutSeconds: 30
        audit:
            enabled: true
```

### 4. Add `.mcp.json` to your project root

Create `.mcp.json` in your host app's project root (adjust the port to match your app):

```json
{
  "mcpServers": {
    "grails": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Or add globally via the Claude CLI:

```bash
claude mcp add grails --transport http http://localhost:8080/mcp
```

### 5. Restart and connect

Start (or restart) your Grails app. You should see the MCP Plugin banner in the console:

```
╔══════════════════════════════════════════════════════════════╗
║              Grails MCP Plugin — Ready                      ║
╠══════════════════════════════════════════════════════════════╣
║  Endpoint:  /mcp                                            ║
║  Tools:     12 registered                                   ║
╠══════════════════════════════════════════════════════════════╣
║  Built-in tools:                                             ║
║    - execute_groovy                                          ║
║    - execute_groovy_transaction                              ║
║    - get_domain_model                                        ║
║    - ...                                                     ║
╚══════════════════════════════════════════════════════════════╝
```

Claude Code will auto-discover the MCP server from `.mcp.json` when you open the project.

## Configuration Reference

### Spring AI MCP Server (`spring.ai.mcp.server.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable the MCP server |
| `name` | -- | Server name reported to MCP clients |
| `version` | -- | Server version reported to MCP clients |
| `protocol` | `STREAMABLE` | MCP transport protocol |
| `type` | `SYNC` | Server type (SYNC or ASYNC) |
| `instructions` | -- | Human-readable server description sent to MCP clients |
| `annotation-scanner.enabled` | `true` | Enable scanning for `@McpTool` annotations |
| `streamable-http.mcp-endpoint` | `/mcp` | HTTP endpoint path for MCP protocol |

### Grails MCP Plugin (`grails.mcp.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `readOnly` | `false` | If `true`, disable all write operations |
| `groovy.timeoutSeconds` | `30` | Maximum execution time for Groovy scripts |
| `groovy.maxOutputBytes` | `524288` | Maximum output size (512KB) before truncation |
| `audit.enabled` | `true` | Enable audit logging of MCP operations |
| `logFile` | (auto-detected) | Path to the application log file. Auto-detects from `logs/<appName>.log`, `logs/application.log`, or `/var/log/app/application.log` |

## Custom Tools

Host applications can register their own MCP tools by creating any Spring bean with `@McpTool` annotated methods. The `McpToolRegistrar` auto-discovers them at startup.

### Example: Adding a custom tool

```groovy
package com.myapp

import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class TicketTools {

    def ticketService  // auto-injected by Grails

    @McpTool(name = "search_tickets",
             description = "Search support tickets by status and keyword")
    String searchTickets(
            @McpToolParam(description = "Ticket status: OPEN, CLOSED, PENDING", required = true)
            String status,
            @McpToolParam(description = "Search keyword for ticket subject/body")
            String keyword) {

        def results = ticketService.search(status, keyword)
        return groovy.json.JsonOutput.prettyPrint(
            groovy.json.JsonOutput.toJson(results)
        )
    }
}
```

**Important:** The correct annotation import is:

```groovy
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
```

Do **not** use `org.springframework.ai.mcp.annotation.McpTool` -- that path does not exist.

### How auto-discovery works

The `McpToolRegistrar` listens for the Spring `ContextRefreshedEvent` (which fires after all beans -- including late-binding Grails plugin beans -- are fully initialized). It then:

1. Loads the plugin's own tool beans by name.
2. Scans every bean in the `ApplicationContext` for methods annotated with `@McpTool`, walking up the class hierarchy to handle CGLIB/Grails proxies.
3. Converts discovered `@McpTool` methods into `ToolCallback` objects via `MethodToolCallbackProvider`.
4. Registers all tools with the `McpSyncServer` and calls `notifyToolsListChanged()`.

This two-strategy approach ensures both plugin tools and host app tools are registered, regardless of when their beans were created in the lifecycle.

## Security

### Groovy Sandbox

The `GroovyExecutorService` runs user-provided scripts in a restricted sandbox:

- **Blacklisted classes** -- the following are blocked from import and use:
  - `java.lang.Runtime`, `java.lang.ProcessBuilder` (no shell commands)
  - `java.io.File`, `java.io.FileInputStream`, `java.io.FileOutputStream` (no filesystem access)
  - `java.lang.System` (no `System.exit()`, no env vars)
  - `java.lang.Thread`, `java.lang.ClassLoader`, `groovy.lang.GroovyClassLoader` (no classloading or threading)
  - `java.lang.reflect.Field` (no reflection)
  - `java.net.URL`, `java.net.Socket` (no network access)
- **Blocked syntax** -- `while` loops are disallowed at the AST level (prevents infinite loops)
- **Execution timeout** -- configurable, default 30 seconds
- **Output cap** -- results truncated at 512KB
- **Dry-run mode** -- `execute_groovy_transaction` with `dryRun=true` executes then rolls back the transaction

### SQL Write Protection

`execute_sql` blocks INSERT, UPDATE, DELETE, DROP, ALTER, and TRUNCATE statements unless the caller explicitly passes `allowWrite=true`.

### Sensitive Config Redaction

`get_app_config` automatically redacts values for any config key containing: `password`, `secret`, `key`, `token`, `credential`, `private`, `apikey`, `api_key`, `auth`, `jwt`.

### Audit Logging

Every `execute_groovy`, `execute_groovy_transaction`, and `execute_sql` call is recorded by `McpAuditService` to a dedicated audit logger (`grails.plugin.mcp.audit`). Payloads are truncated to 500 characters. Configure a dedicated log appender in your `logback.xml`:

```xml
<appender name="MCP_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/mcp-audit.log</file>
    <!-- ... rolling policy ... -->
</appender>
<logger name="grails.plugin.mcp.audit" level="INFO" additivity="false">
    <appender-ref ref="MCP_AUDIT"/>
</logger>
```

## Tech Stack

| Component | Version |
|-----------|---------|
| Grails | 7.0.10 |
| Spring AI BOM | 1.1.4 |
| Spring Boot | 3.5.x |
| MCP Transport | Streamable HTTP (`spring-ai-starter-mcp-server-webmvc`) |
| Build | Gradle with `grails-gradle-plugins` |
| Language | Groovy (compiled with `@CompileDynamic`) |

## Project Structure

```
dc-grails-mcp/
  build.gradle                          # Plugin build, dependencies, publishing
  gradle.properties                     # Grails version, JVM settings
  settings.gradle                       # Plugin management, repositories
  application.yml.example               # Configuration reference for host apps
  META-INF/
    grails-plugin.xml                   # Grails plugin descriptor
    MANIFEST.MF                         # JAR manifest
  src/main/groovy/grails/plugin/mcp/
    GrailsMcpGrailsPlugin.groovy        # Plugin descriptor — registers beans via doWithSpring()
    McpToolRegistrar.groovy             # ContextRefreshedEvent listener — auto-discovers @McpTool beans
    tools/
      GroovyExecutionTools.groovy       # execute_groovy, execute_groovy_transaction
      DomainInspectionTools.groovy      # get_domain_model, get_domain_relationships
      DatabaseTools.groovy              # execute_sql, get_database_schema, analyze_database_issues
      LogTools.groovy                   # get_logs, get_recent_exceptions
      AppInspectionTools.groovy         # get_app_config, get_spring_beans, get_app_health
  grails-app/services/grails/plugin/mcp/
    GroovyExecutorService.groovy        # Sandboxed Groovy script execution
    DomainInspectorService.groovy       # GORM domain class introspection
    DatabaseInspectorService.groovy     # JDBC schema, SQL execution, data quality analysis
    LogReaderService.groovy             # Log file reading and exception grouping
    AppInspectorService.groovy          # Runtime config, Spring beans, health metrics
    McpAuditService.groovy              # Audit logging for MCP operations
```

## License

Apache 2.0
