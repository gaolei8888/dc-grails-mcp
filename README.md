# dc-grails-mcp

Add an MCP server to your Grails 7 app so Claude Code can query your domain model, execute Groovy, run SQL, and read logs -- all from the CLI.

## What is this?

dc-grails-mcp is a Grails plugin that lets Claude Code talk directly to your running Grails application. Once installed, you can ask Claude questions about your app in plain English -- it will inspect your domain model, query your database, run Groovy scripts in your live app context, and read your logs. All from the Claude Code CLI without writing a single line of code yourself.

**What you can do:**
- "What domain classes does this app have?" -- Claude inspects your GORM model
- "How many orders are stuck in PROCESSING?" -- Claude writes and runs the Groovy query for you
- "Any errors in the last hour?" -- Claude reads your logs and groups exceptions
- "Cancel all stale orders -- preview first" -- Claude runs a transaction with dry-run, you review, then commit

The plugin exposes 12 built-in tools and auto-discovers any custom tools you add. It works over the MCP (Model Context Protocol) standard, connecting via HTTP -- no external processes, no Python, no tokens to manage.

## Requirements

- Grails 7 (tested with 7.0.10)
- Java 25 (tested)

## Setup

### Step 1: Add the dependency

First, publish the plugin to your local Maven repo:

```bash
cd dc-grails-mcp
./gradlew publishToMavenLocal
```

Then add it to your app's `build.gradle`:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation group: 'com.dc', name: 'dc-grails-mcp', version: '0.0.1'
}
```

### Step 2: Configure `application.yml`

Add this to your app's `grails-app/conf/application.yml`:

```yaml
spring:
    ai:
        mcp:
            server:
                enabled: true
                name: my-app-mcp
                version: 0.0.1
                protocol: STREAMABLE
                type: SYNC
                annotation-scanner:
                    enabled: true
                streamable-http:
                    mcp-endpoint: /mcp

grails:
    mcp:
        readOnly: false
        groovy:
            timeoutSeconds: 30
        audit:
            enabled: true
```

### Step 3: Connect Claude Code

Create `.mcp.json` in your project root:

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

Change the port to match your app. Start your app, open Claude Code in the project -- done.

## What you get

12 tools, ready to use:

| Tool | What it does |
|------|-------------|
| `gr_groovy` | Run Groovy in your live app -- domain classes, services, ctx all available |
| `gr_groovy_tx` | Same but in a transaction. Use `dryRun: true` to preview changes |
| `gr_domains` | List all GORM domain classes with properties, types, constraints |
| `gr_relationships` | Show hasMany/belongsTo/hasOne across all domains |
| `gr_sql` | Run SQL queries. SELECT by default, DML with `allowWrite: true` |
| `gr_schema` | Get table schemas -- columns, indexes, foreign keys |
| `gr_db_analyze` | Scan for orphaned records, duplicates, missing indexes |
| `gr_logs` | Read app logs with level/pattern filtering |
| `gr_exceptions` | Recent exceptions grouped by type with counts |
| `gr_config` | App config (secrets auto-redacted) |
| `gr_beans` | List Spring beans by name or type |
| `gr_health` | Memory, threads, DB status, versions |

## Examples

You ask questions in plain English. Claude figures out which tool to use and writes the Groovy/SQL for you.

### Explore the data model

```
You: What domain classes does this app have?

Claude calls gr_domains

  Product: { name: String, price: BigDecimal, sku: String, active: Boolean, ... }
  Order: { orderNumber: String, status: String, total: BigDecimal, ... }
  Customer: { email: String, firstName: String, lastName: String, ... }
  OrderItem: { quantity: Integer, unitPrice: BigDecimal, ... }
  Category: { name: String, description: String, ... }
  (18 domain classes total)
```

```
You: How are orders connected to customers?

Claude calls gr_relationships

  Customer:
    hasMany -> [orders: Order, addresses: Address]
  Order:
    belongsTo -> [customer: Customer]
    hasMany -> [items: OrderItem, payments: Payment]
  OrderItem:
    belongsTo -> [order: Order]
```

### Ask questions about your data

Claude writes the Groovy code — you just ask in plain English.

```
You: How many customers signed up this week?

Claude calls gr_groovy
  script: "Customer.countByDateCreatedGreaterThan(new Date() - 7)"

  Result: 89
```

```
You: Who are the top 5 customers by total spending?

Claude calls gr_groovy
  script: """
    Order.executeQuery(
      'SELECT o.customer.email, SUM(o.total) as spent FROM Order o ' +
      'GROUP BY o.customer.email ORDER BY spent DESC', [max: 5]
    )
  """

  Result:
    [["alice@example.com", 4820.00],
     ["bob@example.com", 3150.50],
     ["carol@example.com", 2890.00], ...]
```

```
You: Show me all orders stuck in PROCESSING for more than 2 days

Claude calls gr_groovy
  script: """
    Order.findAllByStatusAndLastUpdatedLessThan('PROCESSING', new Date() - 2)
      .collect { [id: it.id, orderNumber: it.orderNumber, customer: it.customer.email, lastUpdated: it.lastUpdated] }
  """

  Result:
    [id: 501, orderNumber: "ORD-2026-501", customer: "dave@example.com", lastUpdated: "2026-04-10"]
    [id: 487, orderNumber: "ORD-2026-487", customer: "eve@example.com", lastUpdated: "2026-04-09"]
```

```
You: Any products with zero stock?

Claude calls gr_groovy
  script: "Product.findAllByStockQuantity(0).collect { [id: it.id, name: it.name, sku: it.sku] }"

  Result:
    [id: 42, name: "Wireless Mouse", sku: "WM-001"]
    [id: 78, name: "USB-C Hub", sku: "HUB-003"]
```

### Database investigation

```
You: What does the order table look like?

Claude calls gr_schema with { table: "order" }

  columns: [id BIGINT PK, order_number VARCHAR(50), status VARCHAR(20),
            total DECIMAL(19,2), customer_id BIGINT FK->customer, ...]
  indexes: [idx_order_status (status), idx_order_customer (customer_id)]
  foreignKeys: [customer_id -> customer.id]
```

```
You: Are there any orphaned records?

Claude calls gr_db_analyze with { focus: "integrity" }

  HIGH: 5 orphaned order_item rows (order_id references deleted orders)
  MEDIUM: 2 payment rows with null order_id
```

```
You: How many orders per status?

Claude calls gr_sql
  sql: "SELECT status, COUNT(*) as cnt FROM `order` GROUP BY status ORDER BY cnt DESC"

  Result:
    DELIVERED:   1,247
    SHIPPED:       183
    PROCESSING:     41
    CANCELLED:      28
```

### Safe data changes with preview

The dry-run feature lets you see exactly what would change before committing.

```
You: Cancel all processing orders older than 30 days. Show me what would happen first.

Claude calls gr_groovy_tx with { dryRun: true }
  script: """
    def stale = Order.findAllByStatusAndDateCreatedLessThan('PROCESSING', new Date() - 30)
    stale.each { it.status = 'CANCELLED'; it.save() }
    return [cancelled: stale.size(), orderNumbers: stale.collect { it.orderNumber }]
  """

  Result: { cancelled: 3, orderNumbers: ["ORD-2026-102", "ORD-2026-087", "ORD-2026-051"] }
  (transaction rolled back - preview only)
```

```
You: OK, go ahead

Claude calls gr_groovy_tx with { dryRun: false }
  (same script)

  Result: { cancelled: 3 } (committed)
```

```
You: Set all products in the "Clearance" category to 50% off, preview first

Claude calls gr_groovy_tx with { dryRun: true }
  script: """
    def items = Product.findAllByCategory(Category.findByName('Clearance'))
    items.each { it.price = it.price * 0.5; it.save() }
    return items.collect { [name: it.name, oldPrice: it.price * 2, newPrice: it.price] }
  """

  Result:
    [name: "Wireless Mouse", oldPrice: 29.99, newPrice: 14.99]
    [name: "USB-C Hub", oldPrice: 49.99, newPrice: 24.99]
  (rolled back)
```

### Debugging

```
You: Any errors recently?

Claude calls gr_exceptions with { sinceMinutes: 60 }

  NullPointerException (3 times) - "Cannot get property 'email' on null object"
  TimeoutException (1 time) - "Connection timed out after 30000ms"
```

```
You: Show me the null pointer errors

Claude calls gr_logs with { level: "ERROR", pattern: "NullPointer", lines: 10 }

  2026-04-14 08:45:00 ERROR - NullPointerException in OrderService.sendConfirmation
  2026-04-14 08:32:00 ERROR - NullPointerException in OrderService.sendConfirmation
  2026-04-14 08:12:00 ERROR - NullPointerException in OrderService.sendConfirmation
```

```
You: Is the database connection healthy?

Claude calls gr_health

  memory: 456MB / 2048MB (22%)
  threads: 42
  database: UP (MySQL 8.0.35)
```

### Bulk operations

```
You: Deactivate all products that haven't been ordered in 6 months

Claude calls gr_groovy_tx with { dryRun: true }
  script: """
    def cutoff = new Date() - 180
    def stale = Product.executeQuery(
      'FROM Product p WHERE p.active = true AND p.id NOT IN ' +
      '(SELECT DISTINCT oi.product.id FROM OrderItem oi WHERE oi.dateCreated > :cutoff)',
      [cutoff: cutoff])
    stale.each { it.active = false; it.save() }
    return "Deactivated ${stale.size()} products"
  """

  Result: "Deactivated 23 products" (rolled back)
```

```
You: Merge duplicate customer records for alice@example.com

Claude calls gr_groovy_tx with { dryRun: true }
  script: """
    def dupes = Customer.findAllByEmail('alice@example.com', [sort: 'dateCreated'])
    if (dupes.size() < 2) return "No duplicates found"
    def keep = dupes[0]
    def remove = dupes[1..-1]
    remove.each { dupe ->
      Order.findAllByCustomer(dupe).each { it.customer = keep; it.save() }
      dupe.delete()
    }
    return "Merged ${remove.size()} duplicates into Customer #${keep.id}"
  """

  Result: "Merged 1 duplicates into Customer #42" (rolled back)
```

## Adding your own tools

The plugin auto-discovers any bean with `@Tool` annotations at startup. Just create a Grails service with `@Tool` methods and register the bean — it will show up alongside the built-in tools.

See `application.yml.example` in this repo for a full configuration reference.

## Security

- Groovy scripts run in a sandbox — dangerous classes blocked, 30s timeout, 512KB output cap
- SQL write operations blocked by default — must explicitly enable with `allowWrite: true`
- Config values containing passwords/secrets/keys/tokens are auto-redacted
- All tool calls are audit-logged

## License

MIT
