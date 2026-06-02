package com.distributed.site1.service;

import com.distributed.site1.model.Employee;
import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Predicate;

/**
 * SemiJoinService — Site 1 orchestrator
 *
 * Implements the distributed semi-join protocol (R ⋉ S):
 *
 *   R = Employees (Site 1, 10,000 rows)
 *   S = Assignments (Site 2, 50,000 rows)
 *   Goal: Find employees working on at least one project
 *
 * Semi-Join Protocol Steps:
 *   1. Site 2 → Site 1: Send π_EmpID(S)  — only DISTINCT join attribute values
 *   2. Site 1:          Filter R where R.EmpID ∈ π_EmpID(S)  → R' (reduced relation)
 *   3. Site 1 → Site 2: Send R' (much smaller than R)
 *   4. Site 2:          Compute R' ⋈ S locally → final result
 */
@Service
public class SemiJoinService {

    @Value("${site2.url}")
    private String site2Url;

    private final List<Employee> employees = new ArrayList<>();

    // RestTemplate with 5-second connect + read timeout
    // → If Site 2 is down, calls fail fast instead of hanging indefinitely
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);  // 5 s to establish connection
        factory.setReadTimeout(10_000);    // 10 s to read response
        return new RestTemplate(factory);
    }

    // ── Health-check helper ───────────────────────────────────────────────────
    public Map<String, Object> checkSite2Health(String site2Url) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<?, ?> response = restTemplate.getForObject(site2Url + "/site2/info", Map.class);
            result.put("site2_status", "UP");
            result.put("site2_url",    site2Url);
            result.put("site2_info",   response);
        } catch (ResourceAccessException e) {
            result.put("site2_status",  "DOWN");
            result.put("site2_url",     site2Url);
            result.put("error",         "Connection refused — Site 2 is unreachable");
            result.put("failure_type",  "NETWORK_FAILURE");
        } catch (Exception e) {
            result.put("site2_status", "ERROR");
            result.put("error",        e.getMessage());
        }
        return result;
    }

    @PostConstruct
    public void loadData() {
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/data/employees.csv"))))) {

            String[] line;
            reader.readNext(); // skip header

            while ((line = reader.readNext()) != null) {
                employees.add(new Employee(
                        Integer.parseInt(line[0].trim()),  // EmpID
                        line[1].trim(),                    // Name
                        line[2].trim(),                    // Department
                        Integer.parseInt(line[3].trim())   // Salary
                ));
            }
            System.out.println("✅ [Site 1] Loaded " + employees.size() + " employees from CSV");
        } catch (Exception e) {
            System.err.println("❌ [Site 1] Failed to load employees.csv: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SEMI-JOIN EXECUTION: R ⋉ S
    // ═════════════════════════════════════════════════════════════════════════
    public Map<String, Object> executeSemiJoin(String targetDepartment) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> metrics = new LinkedHashMap<>();

        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║        SEMI-JOIN EXECUTION           ║");
        if (targetDepartment != null) {
            System.out.println("║    (With LOCALIZATION Predicate: Dept=" + targetDepartment + ") ║");
        }
        System.out.println("╚══════════════════════════════════════╝");

        // ── Step 0: Localization (Selection Predicate) ────────────────────────
        List<Employee> localEmployees = employees;
        if (targetDepartment != null) {
            System.out.println("🔵 Step 0: Applying Selection Predicate (Localization) locally...");
            localEmployees = employees.stream()
                    .filter(e -> e.getDepartment().equalsIgnoreCase(targetDepartment))
                    .collect(Collectors.toList());
            System.out.printf("   ✔ Pruned relation R: %d / %d employees match Department='%s'%n",
                    localEmployees.size(), employees.size(), targetDepartment);
        }

        // ── Step 1: Get π_EmpID(S) from Site 2 ───────────────────────────────
        System.out.println("[Step 1] Requesting pi_EmpID from Site 2...");
        Map<String, Object> step1Response;
        try {
            step1Response = restTemplate.getForObject(
                    site2Url + "/site2/unique-empids", Map.class);
        } catch (ResourceAccessException e) {
            // ── FAILURE SCENARIO: Site 2 is down ─────────────────────────────
            System.err.println("[FAILURE] Site 2 unreachable at Step 1: " + e.getMessage());
            metrics.put("status",       "FAILED");
            metrics.put("failed_step",  "Step 1 — Could not reach Site 2 to fetch pi_EmpID(S)");
            metrics.put("failure_type", "NETWORK_FAILURE");
            metrics.put("error",        "Site 2 (port 8082) is DOWN — connection refused after 5s timeout");
            metrics.put("impact",       "Semi-Join ABORTED. No data transferred. Site 1 data intact.");
            metrics.put("recovery_hint","Restart Site 2 and retry — Site 1 requires no recovery action");
            metrics.put("execution_time_ms", System.currentTimeMillis() - startTime);
            return metrics;
        }

        List<Integer> projectedEmpIds = (List<Integer>) step1Response.get("empIds");
        long bytesStep1Received = ((Number) step1Response.get("estimatedTransferBytes")).longValue();

        System.out.printf("   OK Received %d distinct EmpIDs from Site 2 (~%d bytes)%n",
                projectedEmpIds.size(), bytesStep1Received);

        // ── Step 2: Filter R locally at Site 1 ────────────────────────────────
        System.out.println("🔵 Step 2: Filtering Employees at Site 1...");
        Set<Integer> empIdSet = new HashSet<>(projectedEmpIds);

        List<Map<String, Object>> filteredEmployees = localEmployees.stream()
                .filter(e -> empIdSet.contains(e.getEmpId()))
                .map(this::toMap)
                .collect(Collectors.toList());

        // Estimate bytes: each employee record ~100 bytes (4 fields as JSON)
        long bytesStep2Sent = (long) filteredEmployees.size() * 100;

        System.out.printf("   ✔ Filtered: %d / %d employees match (~%d bytes to send)%n",
                filteredEmployees.size(), localEmployees.size(), bytesStep2Sent);

        // ── Step 3: Send R' to Site 2 for final join ─────────────────────────
        System.out.println("[Step 3] Sending filtered R' to Site 2 for final join...");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<Map<String, Object>>> request = new HttpEntity<>(filteredEmployees, headers);

        ResponseEntity<Map> step3Response;
        try {
            step3Response = restTemplate.postForEntity(
                    site2Url + "/site2/semijoin-final", request, Map.class);
        } catch (ResourceAccessException e) {
            // ── FAILURE SCENARIO: Site 2 died between Step 1 and Step 3 ─────
            System.err.println("[FAILURE] Site 2 unreachable at Step 3: " + e.getMessage());
            metrics.put("status",       "FAILED");
            metrics.put("failed_step",  "Step 3 — Site 2 went DOWN after Step 1 completed");
            metrics.put("failure_type", "MID_OPERATION_FAILURE");
            metrics.put("error",        "Site 2 crashed mid-operation — partial semi-join aborted");
            metrics.put("impact",       "Steps 1-2 wasted (~" + (bytesStep1Received + bytesStep2Sent) + " bytes transferred). No partial result saved.");
            metrics.put("recovery_hint","This is an atomicity failure — restart Site 2 and re-run the full query");
            metrics.put("execution_time_ms", System.currentTimeMillis() - startTime);
            return metrics;
        }
        Map<String, Object> joinResult = step3Response.getBody();

        long totalTime = System.currentTimeMillis() - startTime;
        long totalBytesSemiJoin = bytesStep1Received + bytesStep2Sent;

        System.out.println("✅ Semi-Join complete in " + totalTime + "ms");

        // ── Build result ──────────────────────────────────────────────────────
        metrics.put("method", targetDepartment == null ? "SEMI-JOIN (R ⋉ S)" : "LOCALIZED SEMI-JOIN (σ(R) ⋉ S)");
//        metrics.put("protocol", List.of(
//                targetDepartment != null ? "Step 0: Site1 Local Pruning: σ_Dept='" + targetDepartment + "'(R)" : "Step 0: No local pruning",
//                "Step 1: Site2 → Site1: π_EmpID(S)  [only distinct EmpIDs]",
//                "Step 2: Site1 filters R locally     [R' = σ_EmpID∈π(R)]",
//                "Step 3: Site1 → Site2: R'           [filtered employees]",
//                "Step 4: Site2 joins R' ⋈ S locally  [final result]"
//        ));
        metrics.put("protocol", List.of(
                targetDepartment != null ? "Step 0: Site1 Local Pruning: R_local = σ_Dept='" + targetDepartment + "'(R)" : "Step 0: No local pruning (R_local = R)",
                "Step 1: Site2 → Site1: V_S = π_EmpID(S)  [only distinct EmpIDs from Site2]",
                "Step 2: Site1 filters R locally     [R' = R_local ⋉ V_S]  (or σ_EmpID∈V_S(R_local))",
                "Step 3: Site1 → Site2: R'           [filtered employees]",
                "Step 4: Site2 joins R' ⋈ S locally  [final result]"
        ));

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("step1_received_from_site2_bytes", bytesStep1Received);
        transfer.put("step1_unique_empids_count", projectedEmpIds.size());
        transfer.put("step2_filtered_employee_count", filteredEmployees.size());
        transfer.put("step3_sent_to_site2_bytes", bytesStep2Sent);
        transfer.put("TOTAL_BYTES_TRANSFERRED", totalBytesSemiJoin);
        metrics.put("data_transfer", transfer);

        metrics.put("execution_time_ms", totalTime);
        metrics.put("join_result", joinResult);
        return metrics;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STANDARD JOIN EXECUTION
    // ═════════════════════════════════════════════════════════════════════════
    public Map<String, Object> executeStandardJoin(String targetDepartment) {
        long startTime = System.currentTimeMillis();

        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║       STANDARD JOIN EXECUTION        ║");
        if (targetDepartment != null) {
            System.out.println("║    (With LOCALIZATION Predicate: Dept=" + targetDepartment + ") ║");
        }
        System.out.println("╚══════════════════════════════════════╝");

        List<Employee> localEmployees = employees;
        if (targetDepartment != null) {
            localEmployees = employees.stream()
                    .filter(e -> e.getDepartment().equalsIgnoreCase(targetDepartment))
                    .collect(Collectors.toList());
        }

        // Send ALL employees to Site 2 (no pre-filtering by Join attribute)
        List<Map<String, Object>> allEmployeeMaps = localEmployees.stream()
                .map(this::toMap)
                .collect(Collectors.toList());

        // Estimate bytes: each employee ~100 bytes
        long bytesSent = (long) allEmployeeMaps.size() * 100;

        System.out.printf("🔴 Sending ALL %d employees to Site 2 (~%d bytes)...%n",
                allEmployeeMaps.size(), bytesSent);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<Map<String, Object>>> request = new HttpEntity<>(allEmployeeMaps, headers);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(
                    site2Url + "/site2/standard-join", request, Map.class);
        } catch (ResourceAccessException e) {
            System.err.println("[FAILURE] Site 2 unreachable during Standard Join: " + e.getMessage());
            Map<String, Object> failMetrics = new LinkedHashMap<>();
            failMetrics.put("status",        "FAILED");
            failMetrics.put("failure_type",  "NETWORK_FAILURE");
            failMetrics.put("error",         "Site 2 (port 8082) is DOWN — Standard Join aborted");
            failMetrics.put("bytes_wasted",  bytesSent + " bytes sent before failure");
            failMetrics.put("impact",        "Entire employee table was shipped to Site 2 but no join result received");
            failMetrics.put("recovery_hint", "Restart Site 2 and retry — demonstrates why Semi-Join wastes less on failure (smaller Step 3 payload)");
            failMetrics.put("execution_time_ms", System.currentTimeMillis() - startTime);
            return failMetrics;
        }

        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("✅ Standard Join complete in " + totalTime + "ms");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("method", targetDepartment == null ? "STANDARD JOIN" : "LOCALIZED STANDARD JOIN");
        metrics.put("protocol", targetDepartment == null ? "Send ALL employees from Site1 to Site2, join at Site2" : "Prune locally (Dept=" + targetDepartment + "), send all matching employees to Site2");
        metrics.put("employees_sent", allEmployeeMaps.size());
        metrics.put("TOTAL_BYTES_TRANSFERRED", bytesSent);
        metrics.put("execution_time_ms", totalTime);
        metrics.put("join_result", response.getBody());
        return metrics;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BENCHMARK — Run both and compare
    // ═════════════════════════════════════════════════════════════════════════
    public Map<String, Object> runBenchmark(String targetDepartment) {
        System.out.println("\n████████████████████████████████████████");
        System.out.println("         BENCHMARK: Standard vs Semi-Join");
        if (targetDepartment != null) {
            System.out.println("         [LOCALIZED: Department='" + targetDepartment + "']");
        }
        System.out.println("████████████████████████████████████████");

        Map<String, Object> standardResult = executeStandardJoin(targetDepartment);
        Map<String, Object> semiJoinResult  = executeSemiJoin(targetDepartment);

        // ── Guard: abort if either sub-call failed (Site 2 down) ─────────────
        if ("FAILED".equals(standardResult.get("status"))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status",        "FAILED");
            err.put("failed_phase",  "Standard Join");
            err.put("error",         standardResult.get("error"));
            err.put("failure_type",  standardResult.get("failure_type"));
            err.put("recovery_hint", "Restart Site 2 (port 8082) and retry /benchmark");
            return err;
        }
        if ("FAILED".equals(semiJoinResult.get("status"))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status",        "FAILED");
            err.put("failed_phase",  "Semi-Join");
            err.put("failed_step",   semiJoinResult.get("failed_step"));
            err.put("error",         semiJoinResult.get("error"));
            err.put("failure_type",  semiJoinResult.get("failure_type"));
            err.put("recovery_hint", "Restart Site 2 (port 8082) and retry /benchmark");
            return err;
        }

        long stdBytes  = (long) standardResult.get("TOTAL_BYTES_TRANSFERRED");
        Map<String, Object> semiJoinTransferMap = (Map<String, Object>) semiJoinResult.get("data_transfer");
        long semiBytes = (long) semiJoinTransferMap.get("TOTAL_BYTES_TRANSFERRED");
        long stdTime   = (long) standardResult.get("execution_time_ms");
        long semiTime  = (long) semiJoinResult.get("execution_time_ms");

        // Size Reduction Factor (SRF): key metric for semi-join analysis
        double srf = (1.0 - (double) semiBytes / stdBytes) * 100.0;

        // Selectivity: ratio of employees who have at least 1 project
        int matchedEmployees = ((Number) semiJoinTransferMap.get("step2_filtered_employee_count")).intValue();
        double selectivity = (double) matchedEmployees / employees.size() * 100.0;

        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("standard_join", standardResult);
        benchmark.put("semi_join", semiJoinResult);

        // ── Formal Cost Modeling (Total_Cost = I/O + CPU + Comm) ─────────────
        // Simulated cost coefficients:
        double COMM_COEFF = 0.05; // cost per byte transferred
        double IO_COEFF = 1.2;    // cost per disk block read
        double CPU_COEFF = 0.01;  // cost per row processed
        
        long totalRowsReadStd = employees.size() + 50000; // Site 1 + Site 2 sizes
        long totalRowsReadSemi = employees.size() + 50000; 

        double stdCommCost = stdBytes * COMM_COEFF;
        double stdIoCost = (employees.size() / 100.0 + 50000 / 100.0) * IO_COEFF; // 100 rows per block
        double stdCpuCost = totalRowsReadStd * CPU_COEFF;
        double stdTotalCost = stdCommCost + stdIoCost + stdCpuCost;

        double semiCommCost = semiBytes * COMM_COEFF;
        double semiIoCost = (employees.size() / 100.0 + 50000 / 100.0) * IO_COEFF;
        double semiCpuCost = totalRowsReadSemi * CPU_COEFF + matchedEmployees * CPU_COEFF; // extra cost for filtering
        double semiTotalCost = semiCommCost + semiIoCost + semiCpuCost;

        Map<String, Object> costModel = new LinkedHashMap<>();
        costModel.put("formula", "Total_Cost = I/O + CPU + Comm");
        costModel.put("Standard_Total_Cost", String.format("%.2f (I/O: %.2f, CPU: %.2f, Comm: %.2f)", stdTotalCost, stdIoCost, stdCpuCost, stdCommCost));
        costModel.put("SemiJoin_Total_Cost", String.format("%.2f (I/O: %.2f, CPU: %.2f, Comm: %.2f)", semiTotalCost, semiIoCost, semiCpuCost, semiCommCost));
        costModel.put("Cost_Saved", String.format("%.2f", stdTotalCost - semiTotalCost));

        // ── Analysis ─────────────────────────────────────────────────────────
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("dataset_employees_site1", employees.size());
        analysis.put("dataset_assignments_site2", "50,000 rows");
        if (targetDepartment != null) {
            analysis.put("localization_predicate", "σ_Department='" + targetDepartment + "'");
        }
        analysis.put("matched_employees_with_project", matchedEmployees);
        analysis.put("selectivity_percent", String.format("%.2f%%", selectivity));

        analysis.put("bytes_transferred_standard_join", stdBytes);
        analysis.put("bytes_transferred_semi_join",     semiBytes);
        analysis.put("bytes_saved",                     stdBytes - semiBytes);

        analysis.put("SIZE_REDUCTION_FACTOR", String.format("%.2f%%", srf));
        analysis.put("size_reduction_formula", "SRF = 1 - (semiJoinBytes / standardBytes)");

        analysis.put("time_standard_ms",  stdTime);
        analysis.put("time_semijoin_ms",  semiTime);
        analysis.put("time_saved_ms",     stdTime - semiTime);
        analysis.put("cost_model_analysis", costModel);

        String conclusion;
        if (srf > 70) {
            conclusion = String.format(
                "✅ Semi-Join is HIGHLY EFFICIENT — reduces data transfer by %.2f%%. " +
                "Low selectivity (%.2f%% employees have assignments) makes semi-join ideal.", srf, selectivity);
        } else if (srf > 30) {
            conclusion = String.format(
                "✅ Semi-Join is MODERATELY EFFICIENT — reduces data transfer by %.2f%%.", srf);
        } else {
            conclusion = String.format(
                "⚠️ Semi-Join overhead is NOT beneficial — only %.2f%% reduction. " +
                "High selectivity means most employees have assignments.", srf);
        }
        analysis.put("conclusion", conclusion);
        analysis.put("reference", "Özsu & Valduriez — Principles of Distributed Database Systems, §5.4");

        benchmark.put("=== ANALYSIS REPORT ===", analysis);

        return benchmark;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Employee e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("empId",      e.getEmpId());
        map.put("name",       e.getName());
        map.put("department", e.getDepartment());
        map.put("salary",     e.getSalary());
        return map;
    }

    public int getTotalEmployees() { return employees.size(); }

    public List<Employee> getAllEmployees() { return Collections.unmodifiableList(employees); }
}
