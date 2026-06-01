package com.distributed.site1.model;

/**
 * Employee entity — stored at Site 1 (10,000 rows)
 */
public class Employee {
    private int empId;
    private String name;
    private String department;
    private int salary;

    public Employee() {}

    public Employee(int empId, String name, String department, int salary) {
        this.empId = empId;
        this.name = name;
        this.department = department;
        this.salary = salary;
    }

    public int getEmpId() { return empId; }
    public void setEmpId(int empId) { this.empId = empId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public int getSalary() { return salary; }
    public void setSalary(int salary) { this.salary = salary; }
}
