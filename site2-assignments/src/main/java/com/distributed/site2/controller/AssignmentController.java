package com.distributed.site2.controller;

import com.distributed.site2.service.AssignmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * AssignmentController — REST API for Site 2
 *
 * Endpoints:
 *  GET  /site2/info             → Site info
 *  GET  /site2/unique-empids    → Step 1 of Semi-Join: return distinct EmpIDs
 *  POST /site2/semijoin-final   → Step 3 of Semi-Join: join with filtered employees
 *  POST /site2/standard-join    → Standard Join: join with ALL employees
 */
@RestController
@RequestMapping("/site2")
@CrossOrigin(origins = "*")
public class AssignmentController {

    @Autowired
    private AssignmentService assignmentService;

    // ── Site info ────────────────────────────────────────────────────────────
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("site", "Site 2 - Assignments");
        info.put("port", 8082);
        info.put("dataset", "assignments.csv");
        info.put("totalRows", assignmentService.getTotalAssignments());
        info.put("uniqueEmpIds", assignmentService.getUniqueEmpCount());
        info.put("status", "UP ✅");
        return ResponseEntity.ok(info);
    }

    // ── SEMI-JOIN Step 1 ─────────────────────────────────────────────────────
    // Site 1 calls this to get the projected join attribute (distinct EmpIDs)
    @GetMapping("/unique-empids")
    public ResponseEntity<Map<String, Object>> getUniqueEmpIds() {
        List<Integer> empIds = assignmentService.getDistinctEmpIds();

        // Each int ~4 bytes on wire (JSON number, average 4-5 chars)
        long estimatedBytes = (long) empIds.size() * 4;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("step", "Semi-Join Step 1: Project(EmpID) from Site 2");
        response.put("empIds", empIds);
        response.put("count", empIds.size());
        response.put("estimatedTransferBytes", estimatedBytes);

        System.out.printf("📤 [Site2 → Site1] Sent %d unique EmpIDs (~%d bytes)%n",
                empIds.size(), estimatedBytes);

        return ResponseEntity.ok(response);
    }

    // ── SEMI-JOIN Step 3 (Final) ──────────────────────────────────────────────
    // Receives filtered employees (R' = filtered result from Site 1)
    // Joins with assignments locally and returns final result
    @PostMapping("/semijoin-final")
    public ResponseEntity<Map<String, Object>> semiJoinFinal(
            @RequestBody List<Map<String, Object>> filteredEmployees) {

        System.out.printf("📥 [Site1 → Site2] Received %d filtered employees for final join%n",
                filteredEmployees.size());

        Map<String, Object> result = assignmentService.performSemiJoinFinal(filteredEmployees);
        return ResponseEntity.ok(result);
    }

    // ── STANDARD JOIN ─────────────────────────────────────────────────────────
    // Receives ALL employees from Site 1 (no pre-filtering)
    @PostMapping("/standard-join")
    public ResponseEntity<Map<String, Object>> standardJoin(
            @RequestBody List<Map<String, Object>> allEmployees) {

        System.out.printf("📥 [Site1 → Site2] Received ALL %d employees for standard join%n",
                allEmployees.size());

        Map<String, Object> result = assignmentService.performStandardJoin(allEmployees);
        return ResponseEntity.ok(result);
    }
}
