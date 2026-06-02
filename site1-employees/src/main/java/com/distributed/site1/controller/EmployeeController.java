package com.distributed.site1.controller;

import com.distributed.site1.service.ExportService;
import com.distributed.site1.service.SemiJoinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EmployeeController — REST API for Site 1 (Orchestrator)
 *
 *  GET /site1/info                        → Dataset info
 *  GET /site1/semijoin                    → Run Semi-Join only (all departments)
 *  GET /site1/semijoin?dept=HR            → Run Semi-Join with localization predicate
 *  GET /site1/standard-join              → Run Standard Join only (all departments)
 *  GET /site1/standard-join?dept=HR      → Run Standard Join with localization predicate
 *  GET /site1/benchmark                  → Run both + Size Reduction Factor analysis
 *  GET /site1/benchmark?dept=HR          → Run both with localization predicate
 *  GET /site1/localized-benchmark?dept=  → Alias for /benchmark?dept= (kept for compatibility)
 *  GET /site1/export                     → Run Semi-Join + export full result to CSV + TXT
 *  GET /site1/export?dept=IT             → Same with localization predicate
 */
@RestController
@RequestMapping("/site1")
@CrossOrigin(origins = "*")
public class EmployeeController {

    @Autowired
    private SemiJoinService semiJoinService;

    @Autowired
    private ExportService exportService;

    @Value("${site2.url}")
    private String site2Url;

    // ── Site info ────────────────────────────────────────────────────────────
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("site", "Site 1 - Employees (Orchestrator)");
        info.put("port", 8081);
        info.put("dataset", "employees.csv");
        info.put("totalRows", semiJoinService.getTotalEmployees());
        info.put("status", "UP ✅");
        info.put("endpoints", Map.of(
                "benchmark",     "GET /site1/benchmark",
                "semijoin",      "GET /site1/semijoin",
                "standard_join", "GET /site1/standard-join",
                "export",        "GET /site1/export  (optional: ?dept=Engineering)"
        ));
        return ResponseEntity.ok(info);
    }

    // ── Semi-Join ────────────────────────────────────────────────────────────
    // GET /site1/semijoin           → full dataset (no filter)
    // GET /site1/semijoin?dept=HR   → localized by department
    @GetMapping("/semijoin")
    public ResponseEntity<Map<String, Object>> runSemiJoin(
            @RequestParam(required = false) String dept) {
        return ResponseEntity.ok(semiJoinService.executeSemiJoin(dept));
    }

    // ── Standard Join ────────────────────────────────────────────────────────
    // GET /site1/standard-join           → full dataset (no filter)
    // GET /site1/standard-join?dept=HR   → localized by department
    @GetMapping("/standard-join")
    public ResponseEntity<Map<String, Object>> runStandardJoin(
            @RequestParam(required = false) String dept) {
        return ResponseEntity.ok(semiJoinService.executeStandardJoin(dept));
    }

    // ── Benchmark (main analysis endpoint) ───────────────────────────────────
    // GET /site1/benchmark           → full dataset (no filter)
    // GET /site1/benchmark?dept=HR   → localized by department
    @GetMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> runBenchmark(
            @RequestParam(required = false) String dept) {
        return ResponseEntity.ok(semiJoinService.runBenchmark(dept));
    }

    // ── Localized Benchmark (alias for /benchmark?dept=) — kept for compatibility ──
    // GET /site1/localized-benchmark?dept=HR
    @GetMapping("/localized-benchmark")
    public ResponseEntity<Map<String, Object>> runLocalizedBenchmark(
            @RequestParam(required = false) String dept) {
        return ResponseEntity.ok(semiJoinService.runBenchmark(dept));
    }

    // ── Export: run Semi-Join → write CSV + summary TXT ──────────────────────
    // GET /site1/export           → full dataset export
    // GET /site1/export?dept=IT   → localized export (prune by department first)
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportResult(
            @RequestParam(required = false) String dept) {
        return ResponseEntity.ok(exportService.exportSemiJoinResult(dept));
    }

    // ── Health-check: ping Site 2 ────────────────────────────────────────────
    // Demo: call this BEFORE and AFTER killing Site 2 to show status change
    @GetMapping("/health-check")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("site1_status", "UP");
        status.put("site1_employees_loaded", semiJoinService.getTotalEmployees());
        status.put("site2_health", semiJoinService.checkSite2Health(site2Url));
        return ResponseEntity.ok(status);
    }
}
