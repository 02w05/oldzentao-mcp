package com.xiaohan.zentao.mcp.model;

/**
 * 描述禅道详情页面中的一个附件。
 *
 * <p>解析器负责从 HTML 中提取这些字段，下载流程随后使用 {@code url} 获取文件，
 * 并使用 {@code name} 生成本地文件名。该记录只承载数据，不执行网络请求或文件写入。</p>
 *
 * @param id 禅道为附件分配的标识；页面未提供时可能为空字符串
 * @param name 页面显示的原始附件名称
 * @param url 解析为绝对地址后的附件下载 URL
 * @param size 页面显示的附件大小文本，例如 {@code 12 KB}
 */
public record AttachmentInfo(
        String id,
        String name,
        String url,
        String size
) {
}
