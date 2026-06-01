import java.io.*;
import java.util.*;

/**
 * GenerateData.java
 * ─────────────────
 * Run this ONCE to generate the two CSV datasets:
 *   - employees.csv   → 10,000 rows  (for Site 1)
 *   - assignments.csv → 50,000 rows  (for Site 2)
 *
 * Key design: only 3,000 out of 10,000 employees have assignments
 * → selectivity = 30% → semi-join is highly effective
 *
 * How to run:
 *   javac GenerateData.java
 *   java GenerateData
 *
 * Then copy:
 *   employees.csv   → site1-employees/src/main/resources/data/
 *   assignments.csv → site2-assignments/src/main/resources/data/
 */
public class GenerateData {

    public static void main(String[] args) throws Exception {
        Random rand = new Random(42); // fixed seed for reproducibility

        String[] departments = {"HR", "IT", "Finance", "Sales", "Engineering", "Marketing", "Legal"};
        String[] firstNames  = {"Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu", "Dang", "Bui", "Do", "Ho"};
        String[] lastNames   = {"An", "Binh", "Chi", "Dung", "Em", "Giang", "Ha", "Kien", "Long", "Mai"};

        // ── Generate employees.csv (Site 1 — 10,000 rows) ────────────────────
        File empFile = new File("employees.csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(empFile)))) {
            pw.println("EmpID,Name,Department,Salary");
            for (int i = 1; i <= 10_000; i++) {
                String name = firstNames[rand.nextInt(firstNames.length)]
                        + " " + lastNames[rand.nextInt(lastNames.length)]
                        + " " + i;
                String dept = departments[rand.nextInt(departments.length)];
                int salary = 20_000 + rand.nextInt(80_000); // 20k–100k
                pw.printf("%d,%s,%s,%d%n", i, name, dept, salary);
            }
        }
        System.out.println("✅ employees.csv created → 10,000 rows");
        System.out.println("   Path: " + empFile.getAbsolutePath());

        // ── Pick 3,000 DISTINCT active employees (30% selectivity) ───────────
        // This means 70% of employees have NO assignments → semi-join filters a lot
        Set<Integer> activeSet = new LinkedHashSet<>();
        while (activeSet.size() < 3_000) {
            activeSet.add(rand.nextInt(10_000) + 1);
        }
        int[] activeEmps = activeSet.stream().mapToInt(Integer::intValue).toArray();
        System.out.printf("   Active employees with assignments: %d / 10,000 (%.0f%% selectivity)%n",
                activeEmps.length, (double) activeEmps.length / 10_000 * 100);

        // ── Generate assignments.csv (Site 2 — 50,000 rows) ──────────────────
        String[] roles    = {"Developer", "Tech Lead", "QA Engineer", "Project Manager", "Business Analyst"};
        String[] projects = {"Alpha", "Beta", "Gamma", "Delta", "Epsilon",
                             "Zeta", "Eta", "Theta", "Iota", "Kappa"};

        File assignFile = new File("assignments.csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(assignFile)))) {
            pw.println("AssignID,EmpID,ProjectID,Role,HoursWorked");
            for (int i = 1; i <= 50_000; i++) {
                int empId       = activeEmps[rand.nextInt(activeEmps.length)];
                int projectId   = rand.nextInt(500) + 1;
                String role     = roles[rand.nextInt(roles.length)];
                int hours       = 10 + rand.nextInt(200);
                pw.printf("%d,%d,%d,%s,%d%n", i, empId, projectId, role, hours);
            }
        }
        System.out.println("✅ assignments.csv created → 50,000 rows");
        System.out.println("   Path: " + assignFile.getAbsolutePath());

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  NEXT STEPS:                                         ║");
        System.out.println("║  Copy employees.csv   → site1-employees/src/main/    ║");
        System.out.println("║                           resources/data/             ║");
        System.out.println("║  Copy assignments.csv → site2-assignments/src/main/  ║");
        System.out.println("║                           resources/data/             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }
}
