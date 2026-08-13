package com.xiaohan.zentao.mcp.model;

/**
 * “我的 Bug”列表中的单条摘要。
 *
 * <p>字段名称与禅道列表接口保持对应，供工具服务直接格式化为 MCP 文本结果；
 * 如需步骤、图片或附件等完整内容，应再根据 {@code id} 查询 {@link BugDetail}。</p>
 *
 * @param id Bug ID
 * @param title Bug 标题
 * @param status 当前状态
 * @param severity 严重程度
 * @param pri 优先级
 * @param openedBy 创建人
 * @param openedDate 创建时间文本
 * @param assignedTo 当前指派人
 * @param projectName 所属项目名称
 * @param bugUrl Bug 详情页地址
 */
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
