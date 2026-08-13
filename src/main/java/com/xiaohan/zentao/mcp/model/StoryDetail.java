package com.xiaohan.zentao.mcp.model;

import java.util.List;

public record StoryDetail(
        String id,
        String title,
        String subtitle,
        String version,
        String background,
        String description,
        String acceptanceCriteria,
        List<String> images,
        List<AttachmentInfo> attachments,
        String storyUrl
) {
}
