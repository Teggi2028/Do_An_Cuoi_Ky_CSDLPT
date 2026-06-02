package com.distributed.site2.service;

import com.distributed.site2.model.Assignment;
import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AssignmentService — Site 2 logic
 *
 * Semi-Join protocol roles:
 *  Step 1: Provide DISTINCT EmpIDs → sent to Site 1 (projection step)
 *  Step 3: Receive filtered Employees from Site 1, perform local join
 */
@Service
public class AssignmentService {

    private final List<Assignment> assignments = new ArrayList<>();

    // ── Pre-computed indexes (built once at startup) ──────────────────────────
    /** DISTINCT EmpIDs from assignments — cached for Step 1 of semi-join protocol */
    private List<Integer> cachedDistinctEmpIds = Collections.emptyList();

    /** empId → Assignment list index for O(1) fan-out lookup during final join */
    private Map<Integer, List<Assignment>> empIdIndex = new HashMap<>();

    @PostConstruct
    public void loadData() {
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/data/assignments.csv"))))) {

            String[] line;
            reader.readNext(); // skip header row

            while ((line = reader.readNext()) != null) {
                assignments.add(new Assignment(
                        Integer.parseInt(line[0].trim()),  // AssignID
                        Integer.parseInt(line[1].trim()),  // EmpID
                        Integer.parseInt(line[2].trim()),  // ProjectID
                        line[3].trim(),                    // Role
                        Integer.parseInt(line[4].trim())   // HoursWorked
                ));
            }
            System.out.println("✅ [Site 2] Loaded " + assignments.size() + " assignments from CSV");

            // ── Build indexes once (O(n) single pass) ────────────────────────
            // Index: empId → list of matching assignments (for O(1) join lookup)
            empIdIndex = new HashMap<>();
            for (Assignment a : assignments) {
                empIdIndex.computeIfAbsent(a.getEmpId(), k -> new ArrayList<>()).add(a);
            }

            // Cache distinct EmpIDs — eliminates repeated stream computation on every request
            cachedDistinctEmpIds = Collections.unmodifiableList(new ArrayList<>(empIdIndex.keySet()));

            System.out.printf("✅ [Site 2] Index built: %d distinct EmpIDs indexed%n", cachedDistinctEmpIds.size());

        } catch (Exception e) {
            System.err.println("❌ [Site 2] Failed to load assignments.csv: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEMI-JOIN — Step 1
    // Returns only DISTINCT EmpIDs (projection of join attribute)
    // FIX: Returns pre-built cache instead of recomputing on every request.
    //      Old code: O(n) stream + sort on every call → now O(1) cache hit.
    // ─────────────────────────────────────────────────────────────────────────
    public List<Integer> getDistinctEmpIds() {
        return cachedDistinctEmpIds; // O(1) — pre-computed at startup
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEMI-JOIN — Step 3 (Final Join)
    // Receives the FILTERED employee list from Site 1.
    // Joins assignments with those employees locally.
    //
    // FIX (Critical — O(n²) → O(n)):
    //   OLD: For each matched assignment, ran filteredEmployees.stream().filter().findFirst()
    //        → O(assignments × filteredEmployees) ≈ 50,000 × 3,000 = 150,000,000 operations!
    //   NEW: Build empLookupMap once (O(k)), then do O(1) map.get() per assignment.
    //        Total: O(assignments + filteredEmployees) ≈ 53,000 operations.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> performSemiJoinFinal(List<Map<String, Object>> filteredEmployees) {
        // Step A: Build O(1) lookup map from received employees  [O(k), k = filteredEmployees.size()]
        Map<Integer, Map<String, Object>> empLookupMap = new HashMap<>(filteredEmployees.size() * 2);
        for (Map<String, Object> emp : filteredEmployees) {
            int id = ((Number) emp.get("empId")).intValue();
            empLookupMap.put(id, emp);
        }

        // Step B: Use pre-built empIdIndex to fan-out only relevant assignments [O(matched)]
        //         empIdIndex: empId → List<Assignment>, built once at startup
        List<Map<String, Object>> joinedRows = new ArrayList<>();
        Set<Integer> seenEmpIds = new HashSet<>();

        for (Integer empId : empLookupMap.keySet()) {
            List<Assignment> matched = empIdIndex.getOrDefault(empId, Collections.emptyList());
            Map<String, Object> emp = empLookupMap.get(empId);

            for (Assignment a : matched) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("empId",       a.getEmpId());
                row.put("empName",     emp.getOrDefault("name",       "N/A"));
                row.put("department",  emp.getOrDefault("department", "N/A"));
                row.put("projectId",   a.getProjectId());
                row.put("role",        a.getRole());
                row.put("hoursWorked", a.getHoursWorked());
                joinedRows.add(row);
                seenEmpIds.add(empId);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalJoinedRows",            joinedRows.size());
        result.put("uniqueEmployeesWithProject",  seenEmpIds.size()); // already tracked above
        result.put("sampleRows",                 joinedRows.stream().limit(5).collect(Collectors.toList()));
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEMI-JOIN FULL — same as above but returns ALL rows (no limit)
    // Used by the export endpoint to write complete result to file
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> performSemiJoinFinalFull(List<Map<String, Object>> filteredEmployees) {
        Map<Integer, Map<String, Object>> empLookupMap = new HashMap<>(filteredEmployees.size() * 2);
        for (Map<String, Object> emp : filteredEmployees) {
            int id = ((Number) emp.get("empId")).intValue();
            empLookupMap.put(id, emp);
        }

        List<Map<String, Object>> joinedRows = new ArrayList<>();
        Set<Integer> seenEmpIds = new HashSet<>();

        for (Integer empId : empLookupMap.keySet()) {
            List<Assignment> matched = empIdIndex.getOrDefault(empId, Collections.emptyList());
            Map<String, Object> emp = empLookupMap.get(empId);
            for (Assignment a : matched) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("empId",       a.getEmpId());
                row.put("empName",     emp.getOrDefault("name",       "N/A"));
                row.put("department",  emp.getOrDefault("department", "N/A"));
                row.put("projectId",   a.getProjectId());
                row.put("role",        a.getRole());
                row.put("hoursWorked", a.getHoursWorked());
                joinedRows.add(row);
                seenEmpIds.add(empId);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalJoinedRows",           joinedRows.size());
        result.put("uniqueEmployeesWithProject", seenEmpIds.size());
        result.put("allRows",                   joinedRows); // full data — no limit
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STANDARD JOIN — receives ALL employees from Site 1
    // Joins with all assignments (no pre-filtering)
    //
    // FIX: Old code scanned `assignments` TWICE (once for count, once for distinct).
    //      New code uses a single-pass loop to collect both metrics simultaneously.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> performStandardJoin(List<Map<String, Object>> allEmployees) {
        Set<Integer> empIdSet = allEmployees.stream()
                .map(e -> ((Number) e.get("empId")).intValue())
                .collect(Collectors.toSet());

        // Single-pass: collect matched row count + distinct empIds simultaneously
        long matchedRows = 0;
        Set<Integer> uniqueEmpIds = new HashSet<>();
        for (Assignment a : assignments) {
            if (empIdSet.contains(a.getEmpId())) {
                matchedRows++;
                uniqueEmpIds.add(a.getEmpId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalJoinedRows",           matchedRows);
        result.put("uniqueEmployeesWithProject", uniqueEmpIds.size());
        result.put("employeesReceived",          allEmployees.size());
        return result;
    }

    public int getTotalAssignments() { return assignments.size(); }

    public int getUniqueEmpCount() {
        return cachedDistinctEmpIds.size(); // O(1) — use cached index
    }
}
