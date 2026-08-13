package com.xiaohan.zentao.mcp.model;

import java.util.List;

/**
 * 禅道需求详情页的结构化结果。
 *
 * <p>需求页面中的背景、描述和验收标准会分别保存，便于 MCP 调用方阅读，
 * 也便于详情存储服务生成本地 Markdown。图片和附件仍由后台任务下载。</p>
 *
 * @param id 需求 ID
 * @param title 需求标题
 * @param subtitle 页面中的副标题或补充标题
 * @param version 当前需求版本
 * @param background 需求背景
 * @param description 需求描述
 * @param acceptanceCriteria 验收标准
 * @param images 正文中图片的绝对 URL 列表
 * @param attachments 页面中可下载的附件列表
 * @param storyUrl 当前需求的禅道详情页地址
 */
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
