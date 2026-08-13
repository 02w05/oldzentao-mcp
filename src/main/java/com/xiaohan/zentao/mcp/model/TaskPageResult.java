package com.xiaohan.zentao.mcp.model;

import java.util.List;

public record TaskPageResult(
        List<TaskInfo> tasks,
        int recTotal,
        int recPerPage,
        int pageTotal,
        int pageID
) {
}
