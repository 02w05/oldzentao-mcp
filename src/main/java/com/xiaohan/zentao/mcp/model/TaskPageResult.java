package com.xiaohan.zentao.mcp.model;

import java.util.List;

/**
 * 禅道任务分页接口的标准化结果。
 *
 * <p>客户端将不同响应位置中的分页字段归一到该记录，工具层无需理解原始 JSON
 * 的嵌套方式即可生成稳定的分页提示。</p>
 *
 * @param tasks 当前页的任务摘要列表
 * @param recTotal 符合条件的总记录数
 * @param recPerPage 服务端采用的每页记录数
 * @param pageTotal 总页数
 * @param pageID 当前页码
 */
public record TaskPageResult(
        List<TaskInfo> tasks,
        int recTotal,
        int recPerPage,
        int pageTotal,
        int pageID
) {
}
