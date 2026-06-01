package com.distributed.site2.model;

/**
 * Assignment entity — stored at Site 2 (50,000 rows)
 * Links employees to projects.
 */
public class Assignment {
    private int assignId;
    private int empId;
    private int projectId;
    private String role;
    private int hoursWorked;

    public Assignment() {}

    public Assignment(int assignId, int empId, int projectId, String role, int hoursWorked) {
        this.assignId = assignId;
        this.empId = empId;
        this.projectId = projectId;
        this.role = role;
        this.hoursWorked = hoursWorked;
    }

    public int getAssignId() { return assignId; }
    public void setAssignId(int assignId) { this.assignId = assignId; }

    public int getEmpId() { return empId; }
    public void setEmpId(int empId) { this.empId = empId; }

    public int getProjectId() { return projectId; }
    public void setProjectId(int projectId) { this.projectId = projectId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public int getHoursWorked() { return hoursWorked; }
    public void setHoursWorked(int hoursWorked) { this.hoursWorked = hoursWorked; }
}
