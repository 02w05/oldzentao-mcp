package com.xiaohan.zentao.mcp.model;

public record TaskInfo(
        String id,
        String projectName,
        String storyID,
        String storyTitle,
        String storyStatus,
        String name,
        String status,
        String openedBy,
        String assignedTo,
        String deadline,
        String realStarted,
        String taskUrl
) {
}
