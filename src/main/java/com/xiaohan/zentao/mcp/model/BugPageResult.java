package com.xiaohan.zentao.mcp.model;

import java.util.List;

public record BugPageResult(
        List<BugInfo> bugs,
        int recTotal,
        int recPerPage,
        int pageTotal,
        int pageID
) {
}
