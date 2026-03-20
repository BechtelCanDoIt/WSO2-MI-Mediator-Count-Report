# WSO2 Synapse Mediator Scanner

A command-line Java tool that recursively scans a directory tree for XML files,
counts WSO2 Synapse / ESB / Micro Integrator mediator usage, and produces a
structured Markdown report.

## Requirements

| Item   | Version  |
|--------|----------|
| Java   | 17+      |
| Maven  | 3.6+     |

No runtime dependencies — the tool uses only JDK built-in XML APIs.

## Build

```bash
cd mediator-scanner
mvn clean package
```

Produces `target/mediator-scanner.jar` (self-contained fat JAR).

## Usage

```
java -jar mediator-scanner.jar <scan-dir> [output-file]
```

| Argument      | Required | Description                                              |
|---------------|----------|----------------------------------------------------------|
| `scan-dir`    | Yes      | Root directory to scan recursively for `.xml` files.     |
| `output-file` | No       | Path for the Markdown report. Defaults to `mediator-count.md` in the current directory. |

### Examples

**Linux — default output:**
```bash
java -jar target/mediator-scanner.jar /opt/wso2/repository/deployment/server/synapse-configs
```

**Linux — explicit output file:**
```bash
java -jar target/mediator-scanner.jar ./my-configs report/mediator-report.md
```

**Windows:**
```cmd
java -jar target\mediator-scanner.jar C:\WSO2\repository\deployment\server\synapse-configs output.md
```

### Progress Indicators

During the scan, the tool prints `.` for each successfully parsed file and `E`
for each parse error. After scanning it prints a summary:

```
.........E...
Files scanned: 13
Parse errors: 1
Report written: /home/user/mediator-count.md
```

## Output Structure

The generated Markdown report contains these sections:

```
# WSO2 Synapse Mediator Usage Report
  (metadata table: scan root, timestamp, file count, error count)

## Grand Total
  (single table — all files merged)

## By Folder
  ### 📁 `/path/to/folder`
  (one table per unique parent directory)

## By File
  ### 📄 `relative/path/to/file.xml`
  (one table per file)

## 🚫 Not Used
  (table of known mediators that were NOT found in any scanned file)

## ⚠️ Parse Errors
  (only present if errors occurred; bullet list)
```

Each mediator table has the format:

```
| Mediator | Category | Count |
|:---------|:---------|------:|
| `log`    | Core     |    14 |
| ...      | ...      |   ... |
|          | **Total**| **25**|
```

## Mediator Categories

| Category                             | Tags                                                                                                   |
|--------------------------------------|--------------------------------------------------------------------------------------------------------|
| Core                                 | `call`, `call-template`, `drop`, `log`, `loopback`, `property`, `variable`, `propertyGroup`, `respond`, `send`, `sequence`, `store` |
| Routing & Conditional Processing     | `filter`, `switch`, `validate`                                                                         |
| Custom & External Invocation         | `class`, `script`                                                                                      |
| Message Transformation               | `enrich`, `header`, `payloadFactory`, `smooks`, `rewrite`, `xquery`, `xslt`, `datamapper`, `fastXSLT`, `jsontransform` |
| Data & Event Handling                | `cache`, `dblookup`, `dbreport`, `dataServiceCall`                                                     |
| Performance & Security               | `throttle`, `transaction`                                                                              |
| Message Processing & Aggregation     | `foreach`, `scatter-gather`                                                                            |
| Security & Authorization             | `NTLM`                                                                                                 |
| Error Handling                       | `throwError`                                                                                           |
| Other / Custom                       | _(any unknown tag in mediator position)_                                                               |

## How Counting Works

### Rule 1 — Known Mediators

Any element whose tag name matches the catalog above is counted **wherever it
appears in the DOM tree**, regardless of its parent.

### Rule 2 — Unknown / Custom Mediators (positional)

If an element's tag is **not** in the known catalog AND it is a **direct child
of a sequence-container element** AND it is **not** in the structural-skip list,
it is counted under `Other / Custom`.

### Sequence-Container Elements

These elements establish "mediator position" — their direct children are
candidates for counting:

`sequence`, `inSequence`, `outSequence`, `faultSequence`, `case`, `default`,
`then`, `else`, `branch`, `onComplete`, `onReject`, `onAccept`

### Structural-Skip List

These elements are **never counted**, even inside a sequence-container:

**Sequence-flow wrappers:** `inSequence`, `outSequence`, `faultSequence`,
`case`, `default`, `then`, `else`, `branch`, `onComplete`, `onReject`, `onAccept`

**Proxy / API / template structure:** `target`, `resource`, `handlers`, `handler`,
`enableAddressing`, `enableRM`, `enableSec`, `enableSecurity`

**Endpoint definition structure:** `endpoint`, `address`, `http`, `wsdlEndpoint`,
`loadbalance`, `failover`, `member`, `suspendOnFailure`, `retryConfig`,
`timeout`, `duration`, `responseAction`, `markForSuspension`, `errorCodes`

**Generic config / metadata:** `parameter`, `description`, `policy`

**Mediator-config sub-elements:** `source`, `format`, `args`, `arg`,
`condition`, `schema`, `feature`, `namespace`, `rules`, `rule`, `with-param`,
`makeforward`, `rewriterule`, `aggregation`, `completeCondition`, `messageCount`

### Known Limitation

`property` and `header` are counted everywhere, including inside `<endpoint>`
configuration blocks. This is intentional — endpoint-level properties are
relatively rare and their inclusion ensures no legitimate mediator usage is
missed.

## Notes for Large Deployments

**Heap tuning:** For very large repositories (10,000+ XML files), increase
the JVM heap:
```bash
java -Xmx2g -jar mediator-scanner.jar /path/to/configs
```

**Parse errors:** Files that fail XML parsing are logged and skipped — they
never abort the scan. The errors section in the report lists each failure.

**Non-Synapse XML:** The scanner processes **all** `.xml` files. Non-Synapse
XML (e.g., Maven POMs, log4j configs) will parse successfully but typically
produce zero mediator counts — they appear in the report with
"_No mediators found._"

## Project Structure

```
mediator-scanner/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── com/wso2/scanner/
                └── MediatorScanner.java
```
# WSO2-MI-Mediator-Count-Report
