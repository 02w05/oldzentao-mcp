package com.xiaohan.zentao.mcp.model;

import java.util.List;

/**
 * 禅道 Bug 详情页的结构化结果。
 *
 * <p>该数据先由 HTML 解析器生成，再由详情存储服务写入本地 JSON；图片和附件列表
 * 仅保存远端资源信息，实际下载由后台下载服务异步完成。</p>
 *
 * @param id Bug ID
 * @param title Bug 标题
 * @param status 当前状态，保留禅道页面返回的原始文本
 * @param severity 严重程度
 * @param steps 重现步骤或详情正文
 * @param images 正文中图片的绝对 URL 列表
 * @param attachments 页面中可下载的附件列表
 * @param bugUrl 当前 Bug 的禅道详情页地址
 */
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
