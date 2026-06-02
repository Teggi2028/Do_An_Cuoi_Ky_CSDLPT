package com.distributed.site1.service;

import com.distributed.site1.model.Employee;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ExportService — Site 1
 *
 * Runs the full Semi-Join protocol, fetches ALL joined rows from Site 2,
 * then writes two output files to ./output/:
 *
 *   1. semijoin_result_<timestamp>.csv   — full join result (EmpID, Name, Dept, ProjectID, Role, Hours)
 *   2. semijoin_summary_<timestamp>.txt  — human-readable benchmark & cost analysis report
 */
@Service
public class ExportService {

    @Value("${site2.url}")
    private String site2Url;

    @Autowired
    private SemiJoinService semiJoinService;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000); // longer timeout — full rows payload can be large
        return new RestTemplate(f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN EXPORT METHOD
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> exportSemiJoinResult(String targetDepartment) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> report = new LinkedHashMap<>();

        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║         EXPORT: SEMI-JOIN RESULT     ║");
        System.out.println("╚══════════════════════════════════════╝");

        // ── Step 0: Localization ──────────────────────────────────────────────
        List<Employee> localEmployees = semiJoinService.getAllEmployees();
        if (targetDepartment != null) {
            localEmployees = localEmployees.stream()
                    .filter(e -> e.getDepartment().equalsIgnoreCase(targetDepartment))
                    .collect(Collectors.toList());
            System.out.printf("🔵 Step 0 [Localization]: %d employees match Dept='%s'%n",
                    localEmployees.size(), targetDepartment);
        }

        // ── Step 1: Get π_EmpID(S) from Site 2 ──────────────────────────────
        System.out.println("🔵 Step 1: Fetching π_EmpID(S) from Site 2...");
        Map<?, ?> step1Raw;
        try {
            step1Raw = restTemplate.getForObject(site2Url + "/site2/unique-empids", Map.class);
        } catch (ResourceAccessException e) {
            report.put("status", "FAILED");
            report.put("error",  "Site 2 unreachable at Step 1 — " + e.getMessage());
            return report;
        }

        List<Integer> projectedEmpIds = (List<Integer>) step1Raw.get("empIds");
        long bytesStep1 = ((Number) step1Raw.get("estimatedTransferBytes")).longValue();
        System.out.printf("   ✔ Received %d distinct EmpIDs (~%d bytes)%n", projectedEmpIds.size(), bytesStep1);

        // ── Step 2: Filter R locally ──────────────────────────────────────────
        Set<Integer> empIdSet = new HashSet<>(projectedEmpIds);
        List<Map<String, Object>> filteredEmployees = localEmployees.stream()
                .filter(e -> empIdSet.contains(e.getEmpId()))
                .map(this::toMap)
                .collect(Collectors.toList());
        long bytesStep3 = (long) filteredEmployees.size() * 100;
        System.out.printf("🔵 Step 2: Filtered %d / %d employees locally%n",
                filteredEmployees.size(), localEmployees.size());

        // ── Step 3: Send R' → Site 2 — get FULL result ───────────────────────
        System.out.println("🔵 Step 3: Sending R' to Site 2 for full join...");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<Map<String, Object>>> request = new HttpEntity<>(filteredEmployees, headers);

        ResponseEntity<Map> step3Response;
        try {
            step3Response = restTemplate.postForEntity(
                    site2Url + "/site2/semijoin-full", request, Map.class);
        } catch (ResourceAccessException e) {
            report.put("status", "FAILED");
            report.put("error",  "Site 2 unreachable at Step 3 — " + e.getMessage());
            return report;
        }

        Map<String, Object> joinResult = step3Response.getBody();
        List<Map<String, Object>> allRows = (List<Map<String, Object>>) joinResult.get("allRows");
        int totalJoinedRows           = ((Number) joinResult.get("totalJoinedRows")).intValue();
        int uniqueEmployeesWithProject = ((Number) joinResult.get("uniqueEmployeesWithProject")).intValue();

        long totalTime       = System.currentTimeMillis() - startTime;
        long totalBytesSemi  = bytesStep1 + bytesStep3;
        long totalBytesStd   = (long) semiJoinService.getTotalEmployees() * 100;
        double srf           = (1.0 - (double) totalBytesSemi / totalBytesStd) * 100.0;
        double selectivity   = (double) uniqueEmployeesWithProject / semiJoinService.getTotalEmployees() * 100.0;

        System.out.printf("✅ Full join complete: %d rows in %dms%n", totalJoinedRows, totalTime);

        // ── Write output files ────────────────────────────────────────────────
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outputDir   = Paths.get("output");

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            report.put("status", "FAILED — cannot create output dir: " + e.getMessage());
            return report;
        }

        Path csvPath     = outputDir.resolve("semijoin_result_" + timestamp + ".csv");
        Path summaryPath = outputDir.resolve("semijoin_summary_" + timestamp + ".txt");

        // ── 1. Write CSV ──────────────────────────────────────────────────────
        writeCsv(csvPath, allRows);
        System.out.println("📄 CSV written → " + csvPath.toAbsolutePath());

        // ── 2. Write Summary TXT ──────────────────────────────────────────────
        writeSummary(summaryPath, targetDepartment, semiJoinService.getTotalEmployees(),
                filteredEmployees.size(), uniqueEmployeesWithProject,
                totalJoinedRows, bytesStep1, bytesStep3, totalBytesSemi,
                totalBytesStd, srf, selectivity, totalTime);
        System.out.println("📄 Summary written → " + summaryPath.toAbsolutePath());

        // ── Build API response ────────────────────────────────────────────────
        report.put("status",           "SUCCESS ✅");
        report.put("exported_csv",     csvPath.toAbsolutePath().toString());
        report.put("exported_summary", summaryPath.toAbsolutePath().toString());
        report.put("total_joined_rows",             totalJoinedRows);
        report.put("unique_employees_with_project", uniqueEmployeesWithProject);
        report.put("execution_time_ms",             totalTime);

        Map<String, Object> transferInfo = new LinkedHashMap<>();
        transferInfo.put("step1_empids_bytes",        bytesStep1);
        transferInfo.put("step3_filtered_emp_bytes",  bytesStep3);
        transferInfo.put("total_bytes_semi_join",     totalBytesSemi);
        transferInfo.put("total_bytes_standard_join", totalBytesStd);
        transferInfo.put("SRF_percent", String.format("%.2f%%", srf));
        report.put("data_transfer", transferInfo);

        // Sample rows (first 10) in API response for quick visual
        report.put("preview_rows_10", allRows.stream().limit(10).collect(Collectors.toList()));

        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write CSV file
    // Columns: EmpID, EmpName, Department, ProjectID, Role, HoursWorked
    // ─────────────────────────────────────────────────────────────────────────
    private void writeCsv(Path path, List<Map<String, Object>> rows) {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // Header
            w.write("EmpID,EmpName,Department,ProjectID,Role,HoursWorked");
            w.newLine();
            // Rows
            for (Map<String, Object> row : rows) {
                w.write(String.format("%s,%s,%s,%s,%s,%s",
                        escape(row.get("empId")),
                        escape(row.get("empName")),
                        escape(row.get("department")),
                        escape(row.get("projectId")),
                        escape(row.get("role")),
                        escape(row.get("hoursWorked"))
                ));
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to write CSV: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write human-readable Summary TXT
    // ─────────────────────────────────────────────────────────────────────────
    private void writeSummary(Path path, String dept,
                              int totalEmp, int filteredEmp, int uniqueEmpWithProject,
                              int totalJoinedRows,
                              long bytesStep1, long bytesStep3, long totalBytesSemi,
                              long totalBytesStd, double srf, double selectivity, long timeMs) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {

            String sep = "═".repeat(60);
            String sep2 = "─".repeat(60);

            w.println(sep);
            w.println("      DISTRIBUTED SEMI-JOIN — ANALYSIS REPORT");
            w.println("      Student: N23DCCN171  |  Subject: CSDLPT");
            w.println("      Generated: " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            w.println(sep);

            w.println();
            w.println("1. DATASET");
            w.println(sep2);
            w.printf("   Site 1 (Orchestrator :8081) — Employees table : %,d rows%n", totalEmp);
            w.printf("   Site 2 (Worker        :8082) — Assignments table: %,d rows%n", 50_000);
            if (dept != null) {
                w.printf("   Localization predicate: σ_Department='%s'  → %,d employees pre-filtered%n", dept, filteredEmp);
            }

            w.println();
            w.println("2. SEMI-JOIN PROTOCOL — DATA TRANSFERRED");
            w.println(sep2);
            w.printf("   Step 1 | Site2 → Site1 | π_EmpID(S)       : %,6d bytes  (%,d distinct EmpIDs × 4B)%n",
                    bytesStep1, bytesStep1 / 4);
            w.printf("   Step 2 | Site1  [local] | Filter R         : 0 bytes    (no network cost)%n");
            w.printf("   Step 3 | Site1 → Site2 | Filtered R'      : %,6d bytes  (%,d employees × 100B)%n",
                    bytesStep3, bytesStep3 / 100);
            w.printf("          ──────────────────────────────────────────%n");
            w.printf("   TOTAL Semi-Join Transfer                   : %,6d bytes%n", totalBytesSemi);
            w.println();
            w.printf("   Standard Join (ship-whole-table) would cost: %,6d bytes%n", totalBytesStd);
            w.printf("   Bytes SAVED by Semi-Join                   : %,6d bytes%n", totalBytesStd - totalBytesSemi);

            w.println();
            w.println("3. SIZE REDUCTION FACTOR (SRF)");
            w.println(sep2);
            w.println("   Formula: SRF = 1 - (semiJoin_bytes / standard_bytes)");
            w.printf("            SRF = 1 - (%,d / %,d) = %.4f%n", totalBytesSemi, totalBytesStd, 1.0 - (double) totalBytesSemi / totalBytesStd);
            w.printf("            SRF = %.2f%% reduction in data transferred%n", srf);
            w.println();
            w.printf("   Selectivity: %.2f%% of employees have at least 1 assignment%n", selectivity);
            if (srf > 70) {
                w.println("   Verdict: ✅ HIGHLY EFFICIENT — low selectivity makes semi-join ideal");
            } else if (srf > 30) {
                w.println("   Verdict: ✅ MODERATELY EFFICIENT");
            } else {
                w.println("   Verdict: ⚠️  NOT BENEFICIAL — high selectivity, consider standard join");
            }

            w.println();
            w.println("4. COST MODEL  (Total_Cost = I/O + CPU + Comm)");
            w.println(sep2);
            double commCoeff = 0.05, ioCoeff = 1.2, cpuCoeff = 0.01;
            long totalRows   = totalEmp + 50_000L;

            double stdComm   = totalBytesStd  * commCoeff;
            double stdIo     = (totalEmp / 100.0 + 50_000 / 100.0) * ioCoeff;
            double stdCpu    = totalRows * cpuCoeff;
            double stdTotal  = stdComm + stdIo + stdCpu;

            double semiComm  = totalBytesSemi * commCoeff;
            double semiIo    = (totalEmp / 100.0 + 50_000 / 100.0) * ioCoeff;
            double semiCpu   = totalRows * cpuCoeff + filteredEmp * cpuCoeff;
            double semiTotal = semiComm + semiIo + semiCpu;

            w.printf("   Coefficients: Comm=%.2f/byte  I/O=%.2f/block  CPU=%.2f/row%n",
                    commCoeff, ioCoeff, cpuCoeff);
            w.println();
            w.printf("   %-30s %14s %14s%n", "Component", "Standard Join", "Semi-Join");
            w.printf("   %-30s %14s %14s%n", sep2.substring(0, 30), "──────────────", "──────────────");
            w.printf("   %-30s %,14.2f %,14.2f%n", "Comm Cost",  stdComm,  semiComm);
            w.printf("   %-30s %,14.2f %,14.2f%n", "I/O Cost",   stdIo,    semiIo);
            w.printf("   %-30s %,14.2f %,14.2f%n", "CPU Cost",   stdCpu,   semiCpu);
            w.printf("   %-30s %14s %14s%n", "", "──────────────", "──────────────");
            w.printf("   %-30s %,14.2f %,14.2f%n", "TOTAL Cost", stdTotal, semiTotal);
            w.printf("   %-30s %14s %,14.2f%n", "Cost Saved", "", stdTotal - semiTotal);

            w.println();
            w.println("5. RESULT STATISTICS");
            w.println(sep2);
            w.printf("   Employees with at least 1 project  : %,d%n", uniqueEmpWithProject);
            w.printf("   Total joined rows (Emp × Assignment): %,d%n", totalJoinedRows);
            w.printf("   Avg assignments per employee        : %.1f%n",
                    uniqueEmpWithProject > 0 ? (double) totalJoinedRows / uniqueEmpWithProject : 0);
            w.printf("   Execution time                      : %d ms%n", timeMs);

            w.println();
            w.println("6. TRADE-OFF ANALYSIS");
            w.println(sep2);
            w.println("   Semi-Join adds 1 extra round-trip (Step 1) compared to Standard Join.");
            w.println("   This increases RESPONSE TIME slightly but dramatically reduces TOTAL COST.");
            w.println("   In WAN / high-latency networks, bandwidth cost >> extra round-trip cost.");
            w.println("   → Semi-Join is the optimal strategy when selectivity < 50% and data is large.");
            w.println();
            w.println("   Reference: Özsu & Valduriez — Principles of Distributed Database Systems, §5.4");

            w.println();
            w.println(sep);
            w.println("  Output file: semijoin_result_*.csv (full join result — see same directory)");
            w.println(sep);
        } catch (IOException e) {
            System.err.println("❌ Failed to write summary: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private String escape(Object val) {
        if (val == null) return "";
        String s = val.toString();
        // Wrap in quotes if contains comma or quote
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private Map<String, Object> toMap(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("empId",      e.getEmpId());
        m.put("name",       e.getName());
        m.put("department", e.getDepartment());
        m.put("salary",     e.getSalary());
        return m;
    }
}
