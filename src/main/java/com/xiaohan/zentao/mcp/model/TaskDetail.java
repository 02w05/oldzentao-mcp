package com.xiaohan.zentao.mcp.model;

import java.util.List;

/**
 * 禅道任务详情页的结构化结果。
 *
 * <p>该记录汇总任务自身信息、关联需求以及详情正文。解析完成后，详情存储服务
 * 会先同步写入 JSON 和 Markdown，再由客户端安排图片及附件的后台下载。</p>
 *
 * @param id 任务 ID
 * @param title 任务标题
 * @param status 当前状态
 * @param projectName 所属项目名称
 * @param storyID 关联需求 ID；未关联需求时可能为空字符串
 * @param storyTitle 关联需求标题
 * @param assignedTo 当前指派人
 * @param deadline 截止日期文本
 * @param description 任务描述正文
 * @param images 正文中图片的绝对 URL 列表
 * @param attachments 页面中可下载的附件列表
 * @param taskUrl 当前任务的禅道详情页地址
 */
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
