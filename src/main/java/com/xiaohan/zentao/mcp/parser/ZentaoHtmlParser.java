package com.xiaohan.zentao.mcp.parser;

import com.xiaohan.zentao.mcp.model.AttachmentInfo;
import com.xiaohan.zentao.mcp.model.BugDetail;
import com.xiaohan.zentao.mcp.model.StoryDetail;
import com.xiaohan.zentao.mcp.model.TaskDetail;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将禅道任务、需求和 Bug 详情页解析为结构化模型。
 *
 * <p>不同禅道版本和主题的页面结构并不完全一致，因此解析过程通常按多个 CSS
 * 选择器和标签布局依次回退，而不是依赖单一 DOM 位置。本类刻意保持无副作用：
 * 只完成 HTML 到 record 的转换，持久化和资源下载交给各自的服务。</p>
 */
public final class ZentaoHtmlParser {

    // 从具体到宽泛排列；后续会选择文本最长的候选，兼容旧版与新版任务页面。
    private static final List<String> TASK_DESCRIPTION_SELECTORS = List.of(
            "#desc .article-content",
            "#desc",
            "#legendDesc .article-content",
            "#legendDesc",
            ".article-content",
            ".content"
    );

    // 以下模式只解析页面展示信息，不承担业务校验。
    private static final Pattern STORY_VERSION = Pattern.compile("^V\\d+(\\.\\d+)*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTACHMENT_SIZE = Pattern.compile("\\((\\d+\\.\\d+[KMG]?)\\)");
    private static final Pattern ATTACHMENT_SUFFIX = Pattern.compile("\\s*\\(\\d+\\.\\d+[KMG]?\\)");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("file-download-(\\d+)");

    /**
     * 解析一份任务详情 HTML。
     *
     * @param taskId 请求对应的任务 ID；页面可能不会在所有区域重复展示它
     * @param baseUrl 禅道根地址，用于生成详情地址及解析相对资源地址
     * @param html 完整任务详情页 HTML
     * @return 结构化任务详情，缺失字段以空字符串或空列表表示
     */
    public TaskDetail parseTask(String taskId, String baseUrl, String html) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(html, "html");

        Document document = Jsoup.parse(html);
        String taskUrl = pageUrl(baseUrl, "task-view-" + taskId + ".html");

        // 正文内可能也包含 h2；页面标题只从正文区域之外的第一个 h2 获取。
        String pageHeading = "";
        for (Element heading : document.select("h2")) {
            if (!hasAncestorMatching(heading, ".article-content, #desc, #legendDesc")) {
                pageHeading = text(heading);
                break;
            }
        }

        // 按页面标题、独立标题和面板标题顺序回退，适配不同页面模板。
        String rawTitle = firstNonEmpty(
                firstText(document, ".page-title"),
                pageHeading,
                firstText(document, ".panel-heading")
        );
        // 标题展示常带任务 ID 或“多人”标记，这些前缀不属于真实任务名称。
        String title = rawTitle
                .replaceAll("\\s+", " ")
                .replaceFirst("^" + Pattern.quote(taskId) + "\\s*", "")
                .replaceFirst("^多人\\s*", "")
                .trim();

        String status = getLabelValue(document, List.of("状态"));
        String projectName = getLabelValue(document, List.of("所属项目", "项目"));
        String storyID = getLabelValue(document, List.of("相关需求", "需求"));
        String storyTitle = firstText(document, "a[href*=story-view]");
        String assignedTo = getLabelValue(document, List.of("指派给", "当前指派"));
        String deadline = getLabelValue(document, List.of("截止日期", "截止"));

        // 多个容器可能同时命中，优先保留内容最完整的最长文本。
        String description = "";
        for (String selector : TASK_DESCRIPTION_SELECTORS) {
            String candidate = firstText(document, selector);
            if (candidate.length() > description.length()) {
                description = candidate;
            }
        }

        if (description.isEmpty()) {
            // 最后的结构无关回退：寻找明确带“任务描述/描述”标签的块级内容。
            for (Element element : document.select("div, td")) {
                String candidate = text(element);
                if ((candidate.contains("任务描述") || candidate.contains("描述"))
                        && candidate.length() > description.length()) {
                    description = candidate
                            .replaceFirst("^任务描述[:：]?", "")
                            .replaceFirst("^描述[:：]?", "")
                            .trim();
                }
            }
        }

        // 图片与附件只收集远端元数据，客户端会在保存详情后安排实际下载。
        return new TaskDetail(
                taskId,
                title,
                status,
                projectName,
                storyID,
                storyTitle,
                assignedTo,
                deadline,
                description,
                collectImages(document, baseUrl),
                collectAttachments(document, baseUrl),
                taskUrl
        );
    }

    /**
     * 解析一份需求详情 HTML。
     *
     * @param storyId 请求对应的需求 ID
     * @param baseUrl 禅道根地址
     * @param html 完整需求详情页 HTML
     * @return 结构化需求详情
     */
    public StoryDetail parseStory(String storyId, String baseUrl, String html) {
        Objects.requireNonNull(storyId, "storyId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(html, "html");

        Document document = Jsoup.parse(html);
        String storyUrl = pageUrl(baseUrl, "story-view-" + storyId + ".html");

        String title = firstNonEmpty(
                firstText(document, "h2"),
                allText(document, ".panel-heading")
        );
        String subtitle = firstText(document, ".text[title]");

        // 常规页面把版本放在表格单元格中，且文本形如 V1、V1.2。
        String version = "";
        for (Element cell : document.select("td")) {
            String candidate = text(cell);
            if (STORY_VERSION.matcher(candidate).matches()) {
                version = candidate;
            }
        }
        if (version.isEmpty()) {
            // 兼容已知的旧页面标记；仍缺失时，从居中文本中保守截取短版本提示。
            version = allText(document, ".old-record-id-HawmdhnkroYIBBxXIaDcNZ9fnEe");
            if (version.isEmpty()) {
                String centeredText = allText(document, "div[style*=\"text-align:center\"]");
                version = centeredText.substring(0, Math.min(10, centeredText.length()));
            }
        }

        String background = "";
        String description = "";
        String acceptanceCriteria = "";
        // 依据区块内的中文标题区分背景、验收标准和普通需求描述。
        for (Element element : document.select(".article-content, .content, #desc")) {
            String candidate = text(element);
            if (candidate.contains("背景") || candidate.contains("需求背景")) {
                background = candidate;
            } else if (candidate.contains("验收") || candidate.contains("验收标准")) {
                acceptanceCriteria = candidate;
            } else if (candidate.length() > 50 && description.isEmpty()) {
                description = candidate;
            }
        }

        return new StoryDetail(
                storyId,
                title,
                subtitle,
                version,
                background,
                description,
                acceptanceCriteria,
                collectImages(document, baseUrl),
                collectAttachments(document, baseUrl),
                storyUrl
        );
    }

    /**
     * 解析一份 Bug 详情 HTML。
     *
     * @param bugId 请求对应的 Bug ID
     * @param baseUrl 禅道根地址
     * @param html 完整 Bug 详情页 HTML
     * @return 结构化 Bug 详情
     */
    public BugDetail parseBug(String bugId, String baseUrl, String html) {
        Objects.requireNonNull(bugId, "bugId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(html, "html");

        Document document = Jsoup.parse(html);
        String bugUrl = pageUrl(baseUrl, "bug-view-" + bugId + ".html");

        String title = firstNonEmpty(
                firstText(document, "h2"),
                allText(document, ".panel-heading")
        );
        String status = followingTdText(document, "th:contains(状态)");
        String severity = followingTdText(document, "th:contains(严重程度)");

        // 重现步骤在不同模板中可能位于多个容器，选择最长文本降低截断风险。
        String steps = "";
        for (Element element : document.select(".article-content, .content, #steps")) {
            String candidate = text(element);
            if (candidate.length() > steps.length()) {
                steps = candidate;
            }
        }

        return new BugDetail(
                bugId,
                title,
                status,
                severity,
                steps,
                collectImages(document, baseUrl),
                collectAttachments(document, baseUrl),
                bugUrl
        );
    }

    /**
     * 按候选中文标签查找对应值，依次兼容表格、定义列表和相邻块布局。
     */
    private String getLabelValue(Document document, List<String> labels) {
        for (String label : labels) {
            // 常见桌面版布局：th 后紧跟包含值的 td。
            Element heading = document.selectFirst("th:contains(" + label + ")");
            String headingValue = nextTextWhenTagIs(heading, "td");
            if (!headingValue.isEmpty()) {
                return headingValue;
            }

            // 部分详情模板使用 dt/dd 定义列表。
            Element term = document.selectFirst("dt:contains(" + label + ")");
            String definitionValue = nextTextWhenTagIs(term, "dd");
            if (!definitionValue.isEmpty()) {
                return definitionValue;
            }

            // 更宽松的回退仅接受直接文本等于标签的单元格，避免命中包含标签的正文。
            for (Element cell : document.select("td:contains(" + label + "), div:contains(" + label + ")")) {
                String ownValue = directText(cell);
                if (ownValue.equals(label) || ownValue.equals(label + ":") || ownValue.equals(label + "：")) {
                    Element next = cell.nextElementSibling();
                    String nextValue = text(next);
                    if (!nextValue.isEmpty()) {
                        return nextValue;
                    }
                    break;
                }
            }
        }
        return "";
    }

    /** 收集非 data URI 图片，并把页面中的相对地址统一解析为绝对 URL。 */
    private List<String> collectImages(Document document, String baseUrl) {
        List<String> images = new ArrayList<>();
        for (Element image : document.select("img")) {
            String source = image.attr("src");
            if (!source.isEmpty() && !source.startsWith("data:")) {
                images.add(resolveUrl(baseUrl, source));
            }
        }
        return images;
    }

    /**
     * 收集附件下载链接，并从链接文本和 href 中拆出名称、大小及附件 ID。
     */
    private List<AttachmentInfo> collectAttachments(Document document, String baseUrl) {
        List<AttachmentInfo> attachments = new ArrayList<>();
        for (Element link : document.select("a[href*=file-download]")) {
            String href = link.attr("href");
            String linkText = text(link);
            // 页面通常把大小附加在名称末尾，模型中的名称需要去掉这部分展示信息。
            String name = ATTACHMENT_SUFFIX.matcher(linkText).replaceFirst("");

            Matcher sizeMatcher = ATTACHMENT_SIZE.matcher(linkText);
            String size = sizeMatcher.find() ? sizeMatcher.group(1) : "";

            if (!href.isEmpty()) {
                // 找不到 ID 时仍保留附件，下载层会使用列表序号构造兜底名称。
                Matcher idMatcher = ATTACHMENT_ID.matcher(href);
                String attachmentId = idMatcher.find() ? idMatcher.group(1) : "";
                attachments.add(new AttachmentInfo(
                        attachmentId,
                        name,
                        resolveUrl(baseUrl, href),
                        size
                ));
            }
        }
        return attachments;
    }

    /** 判断元素的任意祖先是否匹配选择器，用于排除正文内部的标题。 */
    private boolean hasAncestorMatching(Element element, String selector) {
        for (Element parent : element.parents()) {
            if (parent.is(selector)) {
                return true;
            }
        }
        return false;
    }

    /** 获取第一个匹配元素的完整文本；没有匹配时返回空串。 */
    private String firstText(Document document, String selector) {
        return text(document.selectFirst(selector));
    }

    /** 拼接全部匹配元素的原始文本，适合跨多个节点分布的展示值。 */
    private String allText(Document document, String selector) {
        StringBuilder combined = new StringBuilder();
        for (Element element : document.select(selector)) {
            combined.append(element.wholeText());
        }
        return combined.toString().trim();
    }

    /** 拼接匹配表头之后紧邻的 td 文本。 */
    private String followingTdText(Document document, String selector) {
        StringBuilder combined = new StringBuilder();
        for (Element element : document.select(selector)) {
            Element next = element.nextElementSibling();
            if (next != null && next.tagName().equals("td")) {
                combined.append(next.wholeText());
            }
        }
        return combined.toString().trim();
    }

    /** 仅当下一个兄弟元素标签符合预期时返回其文本。 */
    private String nextTextWhenTagIs(Element element, String expectedTag) {
        if (element == null) {
            return "";
        }
        Element next = element.nextElementSibling();
        return next != null && next.tagName().equals(expectedTag) ? text(next) : "";
    }

    /**
     * 只读取元素自身的文本节点，不混入子元素文字；用于精确判断标签单元格。
     */
    private String directText(Element element) {
        StringBuilder directText = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                directText.append(textNode.getWholeText());
            }
        }
        return directText.toString().trim();
    }

    /** 对可能不存在的元素执行空安全文本提取。 */
    private String text(Element element) {
        return element == null ? "" : element.wholeText().trim();
    }

    /** 按参数顺序返回第一个非空文本，全部为空时返回空串。 */
    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** 在去掉根地址尾部斜杠后拼接一个站内页面路径。 */
    private String pageUrl(String baseUrl, String page) {
        return stripTrailingSlash(baseUrl) + "/" + page;
    }

    /**
     * 按 URI 规则解析相对引用；遇到不符合 URI 语法的页面值时退回简单站内拼接。
     */
    private String resolveUrl(String baseUrl, String reference) {
        try {
            URI normalizedBase = URI.create(stripTrailingSlash(baseUrl) + "/");
            return normalizedBase.resolve(URI.create(reference)).normalize().toString();
        } catch (IllegalArgumentException ignored) {
            return pageUrl(baseUrl, reference.startsWith("/") ? reference.substring(1) : reference);
        }
    }

    /** 移除任意数量的结尾斜杠，避免后续页面拼接产生重复分隔符。 */
    private String stripTrailingSlash(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }
}
