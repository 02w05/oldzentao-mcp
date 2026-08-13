package com.xiaohan.zentao.mcp.model;

import java.util.List;

/**
 * 禅道 Bug 分页接口的标准化结果。
 *
 * <p>除当前页数据外，同时保留服务端返回的分页元数据，工具层据此向用户展示
 * 当前页、总页数和总记录数。</p>
 *
 * @param bugs 当前页的 Bug 摘要列表
 * @param recTotal 符合条件的总记录数
 * @param recPerPage 服务端采用的每页记录数
 * @param pageTotal 总页数
 * @param pageID 当前页码
 */
public record BugPageResult(
        List<BugInfo> bugs,
        int recTotal,
        int recPerPage,
        int pageTotal,
        int pageID
) {
}
