package com.xiaohan.zentao.mcp.model;

import java.util.List;

public record TaskDetail(
        String id,
        String title,
        String status,
        String projectName,
        String storyID,
        String storyTitle,
        String assignedTo,
        String deadline,
        String description,
        List<String> images,
        List<AttachmentInfo> attachments,
        String taskUrl
) {
}
