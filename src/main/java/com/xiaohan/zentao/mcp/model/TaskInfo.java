package com.xiaohan.zentao.mcp.model;

/**
 * “我的任务”列表中的单条摘要。
 *
 * <p>该记录刻意保留禅道接口中的展示文本，工具服务可以直接将其组织为返回内容。
 * 任务正文、图片和附件不在列表接口中，需要通过任务 ID 另外查询 {@link TaskDetail}。</p>
 *
 * @param id 任务 ID
 * @param projectName 所属项目名称
 * @param storyID 关联需求 ID
 * @param storyTitle 关联需求标题
 * @param storyStatus 关联需求状态
 * @param name 任务名称
 * @param status 任务状态
 * @param openedBy 创建人
 * @param assignedTo 当前指派人
 * @param deadline 截止日期文本
 * @param realStarted 实际开始时间文本
 * @param taskUrl 任务详情页地址
 */
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
