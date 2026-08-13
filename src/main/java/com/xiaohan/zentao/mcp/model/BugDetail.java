package com.xiaohan.zentao.mcp.model;

import java.util.List;

public record BugDetail(
        String id,
        String title,
        String status,
        String severity,
        String steps,
        List<String> images,
        List<AttachmentInfo> attachments,
        String bugUrl
) {
}
