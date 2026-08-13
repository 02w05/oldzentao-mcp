package com.xiaohan.zentao.mcp.model;

public record BugInfo(
        String id,
        String title,
        String status,
        String severity,
        String pri,
        String openedBy,
        String openedDate,
        String assignedTo,
        String projectName,
        String bugUrl
) {
}
