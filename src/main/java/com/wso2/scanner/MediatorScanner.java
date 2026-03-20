package com.wso2.scanner;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WSO2 Synapse Mediator Scanner
 * <p>
 * Recursively scans a directory tree for .xml files, parses each as a
 * Synapse / ESB / MI configuration, counts mediator usage, and writes
 * a Markdown report with Grand Total, By Folder, By File, Not Used,
 * and Parse Errors sections.
 */
public final class MediatorScanner {

    // ──────────────────────────────────────────────────────────────────
    // 1. Mediator Catalog  (tag → category, insertion-ordered)
    // ──────────────────────────────────────────────────────────────────

    /** Tag name → category label. Insertion order = category display order within a category. */
    private static final LinkedHashMap<String, String> TAG_TO_CATEGORY = new LinkedHashMap<>();

    /** Ordered list of category labels (display order in tables). */
    private static final List<String> CATEGORY_ORDER = new ArrayList<>();

    static {
        // Helper: register tags for a category, preserving insertion order.
        class Reg {
            void add(String category, String... tags) {
                if (!CATEGORY_ORDER.contains(category)) CATEGORY_ORDER.add(category);
                for (String t : tags) TAG_TO_CATEGORY.put(t, category);
            }
        }
        Reg r = new Reg();
        r.add("Core",
                "call", "call-template", "drop", "log", "loopback",
                "property", "variable", "propertyGroup", "respond",
                "send", "sequence", "store");
        r.add("Routing & Conditional Processing",
                "filter", "switch", "validate");
        r.add("Custom & External Invocation",
                "class", "script");
        r.add("Message Transformation",
                "enrich", "header", "payloadFactory", "smooks",
                "rewrite", "xquery", "xslt", "datamapper",
                "fastXSLT", "jsontransform");
        r.add("Data & Event Handling",
                "cache", "dblookup", "dbreport", "dataServiceCall");
        r.add("Performance & Security",
                "throttle", "transaction");
        r.add("Message Processing & Aggregation",
                "foreach", "scatter-gather");
        r.add("Security & Authorization",
                "NTLM");
        r.add("Error Handling",
                "throwError");
        // "Other / Custom" is implicit — anything counted but not in the map
        CATEGORY_ORDER.add("Other / Custom");
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Sequence-Container Elements
    // ──────────────────────────────────────────────────────────────────

    private static final Set<String> SEQUENCE_CONTAINERS = Set.of(
            "sequence",
            "inSequence", "outSequence", "faultSequence",
            "case", "default",
            "then", "else",
            "branch",
            "onComplete",
            "onReject", "onAccept"
    );

    // ──────────────────────────────────────────────────────────────────
    // 3. Structural-Skip List
    // ──────────────────────────────────────────────────────────────────

    private static final Set<String> STRUCTURAL_SKIP = Set.of(
            // Sequence-flow wrappers
            "inSequence", "outSequence", "faultSequence",
            "case", "default", "then", "else",
            "branch", "onComplete", "onReject", "onAccept",
            // Proxy / API / template structure
            "target", "resource", "handlers", "handler",
            "enableAddressing", "enableRM", "enableSec", "enableSecurity",
            // Endpoint definition structure
            "endpoint", "address", "http", "wsdlEndpoint",
            "loadbalance", "failover", "member",
            "suspendOnFailure", "retryConfig",
            "timeout", "duration", "responseAction",
            "markForSuspension", "errorCodes",
            // Generic config / metadata
            "parameter", "description", "policy",
            // Mediator-config sub-elements
            "source", "format", "args", "arg",
            "condition", "schema", "feature", "namespace",
            "rules", "rule", "with-param", "makeforward", "rewriterule",
            "aggregation", "completeCondition", "messageCount"
    );

    // ──────────────────────────────────────────────────────────────────
    // 4. File Result Record
    // ──────────────────────────────────────────────────────────────────

    record FileResult(Path path, Map<String, Integer> counts) {}

    // ──────────────────────────────────────────────────────────────────
    // 5. Entry Point
    // ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -jar mediator-scanner.jar <scan-dir> [output-file]");
            System.exit(1);
        }

        Path scanDir = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(scanDir)) {
            System.err.println("Error: not a directory — " + scanDir);
            System.exit(1);
        }

        Path outputFile = (args.length == 2)
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("mediator-count.md").toAbsolutePath().normalize();

        // Collect XML files
        List<Path> xmlFiles = new ArrayList<>();
        try {
            Files.walkFileTree(scanDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                        xmlFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error walking directory: " + e.getMessage());
            System.exit(1);
        }

        // Sort for deterministic output
        Collections.sort(xmlFiles);

        // Set up DOM parser (reuse across files)
        DocumentBuilder db;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            db = dbf.newDocumentBuilder();
            db.setErrorHandler(null); // suppress recoverable-warning stderr noise
        } catch (Exception e) {
            System.err.println("Failed to initialize XML parser: " + e.getMessage());
            System.exit(1);
            return; // unreachable but keeps compiler happy
        }

        // Parse files
        List<FileResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Path xmlFile : xmlFiles) {
            try {
                Document doc = db.parse(xmlFile.toFile());
                Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                walkElement(doc.getDocumentElement(), counts, false);
                results.add(new FileResult(xmlFile, counts));
                System.out.print(".");
            } catch (Exception e) {
                errors.add(xmlFile + " → " + e.getMessage());
                System.out.print("E");
            }
            db.reset();
        }
        System.out.println();

        // Write report
        try {
            writeReport(scanDir, outputFile, results, errors);
        } catch (IOException e) {
            System.err.println("Error writing report: " + e.getMessage());
            System.exit(1);
        }

        System.out.printf("Files scanned: %d%n", results.size() + errors.size());
        System.out.printf("Parse errors: %d%n", errors.size());
        System.out.printf("Report written: %s%n", outputFile);
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Recursive DOM Walk
    // ──────────────────────────────────────────────────────────────────

    private static void walkElement(Element el, Map<String, Integer> counts, boolean parentIsSeqContainer) {
        String name = el.getLocalName();
        if (name == null) name = el.getTagName();

        boolean isKnown      = TAG_TO_CATEGORY.containsKey(name);
        boolean isStructural  = STRUCTURAL_SKIP.contains(name);
        boolean isContainer   = SEQUENCE_CONTAINERS.contains(name);

        if (!isStructural && (isKnown || parentIsSeqContainer)) {
            counts.merge(name, 1, Integer::sum);
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                walkElement((Element) child, counts, isContainer);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Map Merge Helper
    // ──────────────────────────────────────────────────────────────────

    private static Map<String, Integer> merge(List<Map<String, Integer>> maps) {
        Map<String, Integer> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, Integer> m : maps) {
            m.forEach((k, v) -> merged.merge(k, v, Integer::sum));
        }
        return merged;
    }

    // ──────────────────────────────────────────────────────────────────
    // 8. Report Writer
    // ──────────────────────────────────────────────────────────────────

    private static void writeReport(Path scanDir, Path outputFile,
                                    List<FileResult> results,
                                    List<String> errors) throws IOException {

        Files.createDirectories(outputFile.getParent());

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {

            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            int totalFiles = results.size() + errors.size();

            // ── Header ──
            pw.println("# WSO2 Synapse Mediator Usage Report");
            pw.println();
            pw.println("| | |");
            pw.println("|:---|:---|");
            pw.printf("| **Scan Root**         | `%s` |%n", scanDir);
            pw.printf("| **Generated**         | %s |%n", now);
            pw.printf("| **XML Files Scanned** | %,d |%n", totalFiles);
            pw.printf("| **Parse Errors**      | %,d |%n", errors.size());

            // ── Grand Total ──
            Map<String, Integer> grandTotal = merge(
                    results.stream().map(FileResult::counts).collect(Collectors.toList()));

            pw.println();
            pw.println("---");
            pw.println();
            pw.println("## Grand Total");
            pw.println();
            writeMediatorTable(pw, grandTotal);

            // ── By Folder ──
            TreeMap<Path, List<FileResult>> byFolder = new TreeMap<>();
            for (FileResult fr : results) {
                byFolder.computeIfAbsent(fr.path().getParent(), k -> new ArrayList<>()).add(fr);
            }

            pw.println();
            pw.println("---");
            pw.println();
            pw.println("## By Folder");

            for (Map.Entry<Path, List<FileResult>> entry : byFolder.entrySet()) {
                Path folder = entry.getKey();
                List<FileResult> folderResults = entry.getValue();
                Map<String, Integer> folderCounts = merge(
                        folderResults.stream().map(FileResult::counts).collect(Collectors.toList()));

                pw.println();
                pw.printf("### \uD83D\uDCC1 `%s`%n", folder);
                pw.printf("_%,d file(s)_%n", folderResults.size());
                pw.println();
                writeMediatorTable(pw, folderCounts);
            }

            // ── By File ──
            pw.println();
            pw.println("---");
            pw.println();
            pw.println("## By File");

            for (FileResult fr : results) {
                Path rel = scanDir.relativize(fr.path());
                pw.println();
                pw.printf("### \uD83D\uDCC4 `%s`%n", rel);
                pw.println();
                writeMediatorTable(pw, fr.counts());
            }

            // ── Not Used ──
            Set<String> usedMediators = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            usedMediators.addAll(grandTotal.keySet());

            // Find known mediators that never appeared
            List<String> notUsed = new ArrayList<>();
            for (String tag : TAG_TO_CATEGORY.keySet()) {
                if (!usedMediators.contains(tag)) {
                    notUsed.add(tag);
                }
            }

            if (!notUsed.isEmpty()) {
                pw.println();
                pw.println("---");
                pw.println();
                pw.println("## \uD83D\uDEAB Not Used");
                pw.println();
                pw.println("The following known mediators were **not detected** in any scanned file:");
                pw.println();
                pw.println("| Mediator | Category |");
                pw.println("|:---------|:---------|");
                // Sort by category order, then alphabetically within category
                notUsed.sort((a, b) -> {
                    String catA = TAG_TO_CATEGORY.getOrDefault(a, "Other / Custom");
                    String catB = TAG_TO_CATEGORY.getOrDefault(b, "Other / Custom");
                    int ci = Integer.compare(CATEGORY_ORDER.indexOf(catA), CATEGORY_ORDER.indexOf(catB));
                    if (ci != 0) return ci;
                    return a.compareToIgnoreCase(b);
                });
                for (String tag : notUsed) {
                    String cat = TAG_TO_CATEGORY.getOrDefault(tag, "Other / Custom");
                    pw.printf("| `%s` | %s |%n", tag, cat);
                }
            }

            // ── Parse Errors ──
            if (!errors.isEmpty()) {
                pw.println();
                pw.println("---");
                pw.println();
                pw.println("## \u26A0\uFE0F Parse Errors");
                pw.println();
                for (String err : errors) {
                    pw.printf("- %s%n", err);
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 9. Mediator Table Renderer
    // ──────────────────────────────────────────────────────────────────

    private static void writeMediatorTable(PrintWriter pw, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            pw.println("_No mediators found._");
            return;
        }

        // Bucket by category
        LinkedHashMap<String, List<Map.Entry<String, Integer>>> buckets = new LinkedHashMap<>();
        for (String cat : CATEGORY_ORDER) {
            buckets.put(cat, new ArrayList<>());
        }

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String cat = TAG_TO_CATEGORY.getOrDefault(e.getKey(), "Other / Custom");
            buckets.computeIfAbsent(cat, k -> new ArrayList<>()).add(e);
        }

        // Sort within each bucket: count descending, then name ascending
        for (List<Map.Entry<String, Integer>> list : buckets.values()) {
            list.sort((a, b) -> {
                int cmp = Integer.compare(b.getValue(), a.getValue());
                return cmp != 0 ? cmp : a.getKey().compareToIgnoreCase(b.getKey());
            });
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        pw.println("| Mediator | Category | Count |");
        pw.println("|:---------|:---------|------:|");

        for (Map.Entry<String, List<Map.Entry<String, Integer>>> bucket : buckets.entrySet()) {
            for (Map.Entry<String, Integer> e : bucket.getValue()) {
                pw.printf("| `%s` | %s | %,d |%n", e.getKey(), bucket.getKey(), e.getValue());
            }
        }

        pw.printf("| | **Total** | **%,d** |%n", total);
    }
}
