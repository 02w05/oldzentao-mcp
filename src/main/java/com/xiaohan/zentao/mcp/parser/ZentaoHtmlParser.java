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

public final class ZentaoHtmlParser {

    private static final List<String> TASK_DESCRIPTION_SELECTORS = List.of(
            "#desc .article-content",
            "#desc",
            "#legendDesc .article-content",
            "#legendDesc",
            ".article-content",
            ".content"
    );

    private static final Pattern STORY_VERSION = Pattern.compile("^V\\d+(\\.\\d+)*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTACHMENT_SIZE = Pattern.compile("\\((\\d+\\.\\d+[KMG]?)\\)");
    private static final Pattern ATTACHMENT_SUFFIX = Pattern.compile("\\s*\\(\\d+\\.\\d+[KMG]?\\)");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("file-download-(\\d+)");

    public TaskDetail parseTask(String taskId, String baseUrl, String html) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(html, "html");

        Document document = Jsoup.parse(html);
        String taskUrl = pageUrl(baseUrl, "task-view-" + taskId + ".html");

        String pageHeading = "";
        for (Element heading : document.select("h2")) {
            if (!hasAncestorMatching(heading, ".article-content, #desc, #legendDesc")) {
                pageHeading = text(heading);
                break;
            }
        }

        String rawTitle = firstNonEmpty(
                firstText(document, ".page-title"),
                pageHeading,
                firstText(document, ".panel-heading")
        );

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

        // 旧版主题可能同时命中多个容器，取最长文本作为任务描述。
        String description = "";
        for (String selector : TASK_DESCRIPTION_SELECTORS) {
            String candidate = firstText(document, selector);
            if (candidate.length() > description.length()) {
                description = candidate;
            }
        }

        if (description.isEmpty()) {
            // 非标准主题没有固定容器时，退回到带中文描述标签的块级内容。
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

        String version = "";
        for (Element cell : document.select("td")) {
            String candidate = text(cell);
            if (STORY_VERSION.matcher(candidate).matches()) {
                version = candidate;
            }
        }
        if (version.isEmpty()) {
            // 兼容不使用标准版本单元格的旧版主题。
            version = allText(document, ".old-record-id-HawmdhnkroYIBBxXIaDcNZ9fnEe");
            if (version.isEmpty()) {
                String centeredText = allText(document, "div[style*=\"text-align:center\"]");
                version = centeredText.substring(0, Math.min(10, centeredText.length()));
            }
        }

        String background = "";
        String description = "";
        String acceptanceCriteria = "";

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

    private String getLabelValue(Document document, List<String> labels) {
        // 12.x 主题会把标签渲染成 th/td、dt/dd 或相邻的普通单元格。
        for (String label : labels) {
            Element heading = document.selectFirst("th:contains(" + label + ")");
            String headingValue = nextTextWhenTagIs(heading, "td");
            if (!headingValue.isEmpty()) {
                return headingValue;
            }

            Element term = document.selectFirst("dt:contains(" + label + ")");
            String definitionValue = nextTextWhenTagIs(term, "dd");
            if (!definitionValue.isEmpty()) {
                return definitionValue;
            }

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

    private List<AttachmentInfo> collectAttachments(Document document, String baseUrl) {
        List<AttachmentInfo> attachments = new ArrayList<>();
        for (Element link : document.select("a[href*=file-download]")) {
            String href = link.attr("href");
            String linkText = text(link);

            String name = ATTACHMENT_SUFFIX.matcher(linkText).replaceFirst("");
            Matcher sizeMatcher = ATTACHMENT_SIZE.matcher(linkText);
            String size = sizeMatcher.find() ? sizeMatcher.group(1) : "";

            if (!href.isEmpty()) {
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

    private boolean hasAncestorMatching(Element element, String selector) {
        for (Element parent : element.parents()) {
            if (parent.is(selector)) {
                return true;
            }
        }
        return false;
    }

    private String firstText(Document document, String selector) {
        return text(document.selectFirst(selector));
    }

    private String allText(Document document, String selector) {
        StringBuilder combined = new StringBuilder();
        for (Element element : document.select(selector)) {
            combined.append(element.wholeText());
        }
        return combined.toString().trim();
    }

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

    private String nextTextWhenTagIs(Element element, String expectedTag) {
        if (element == null) {
            return "";
        }
        Element next = element.nextElementSibling();
        return next != null && next.tagName().equals(expectedTag) ? text(next) : "";
    }

    private String directText(Element element) {
        StringBuilder directText = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                directText.append(textNode.getWholeText());
            }
        }
        return directText.toString().trim();
    }

    private String text(Element element) {
        return element == null ? "" : element.wholeText().trim();
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String pageUrl(String baseUrl, String page) {
        return stripTrailingSlash(baseUrl) + "/" + page;
    }

    private String resolveUrl(String baseUrl, String reference) {
        try {
            URI normalizedBase = URI.create(stripTrailingSlash(baseUrl) + "/");
            return normalizedBase.resolve(URI.create(reference)).normalize().toString();
        } catch (IllegalArgumentException ignored) {
            return pageUrl(baseUrl, reference.startsWith("/") ? reference.substring(1) : reference);
        }
    }

    private String stripTrailingSlash(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }
}
