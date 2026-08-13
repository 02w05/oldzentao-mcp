package com.xiaohan.zentao.mcp.client;

import com.xiaohan.zentao.mcp.model.AttachmentInfo;
import com.xiaohan.zentao.mcp.model.BugDetail;
import com.xiaohan.zentao.mcp.model.BugInfo;
import com.xiaohan.zentao.mcp.model.BugPageResult;
import com.xiaohan.zentao.mcp.model.StoryDetail;
import com.xiaohan.zentao.mcp.model.TaskDetail;
import com.xiaohan.zentao.mcp.model.TaskInfo;
import com.xiaohan.zentao.mcp.model.TaskPageResult;
import com.xiaohan.zentao.mcp.parser.ZentaoHtmlParser;
import com.xiaohan.zentao.mcp.storage.DetailStore;
import com.xiaohan.zentao.mcp.storage.DownloadService;
import com.xiaohan.zentao.mcp.util.AtomicFiles;
import com.xiaohan.zentao.mcp.util.FileNames;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZentaoClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String LOGIN_PAGE_INDICATOR = "user-login";
    private static final DateTimeFormatter ZENTAO_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,10}$");

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ZentaoHtmlParser htmlParser;
    private final DetailStore detailStore;
    private final DownloadService downloadService;

    // 锁与会话代数共同避免多个并发请求重复刷新同一个过期会话。
    private final Object authenticationLock = new Object();
    private final AtomicLong sessionGeneration = new AtomicLong();

    // 凭据仅在内存中保留，用于 Cookie 会话过期后的单次自动重登录。
    private volatile String loggedInAccount;
    private volatile String savedPassword;

    // 旧版第二页路由必须携带从首页响应获得的总记录数。
    private volatile Integer cachedTaskTotal;
    private volatile Integer cachedBugTotal;

    public ZentaoClient(
        String baseUrl,
        DetailStore detailStore,
        DownloadService downloadService
    ) {
        this(baseUrl, detailStore, downloadService, JsonMapper.builder().build(), new ZentaoHtmlParser());
    }

    ZentaoClient(
        String baseUrl,
        DetailStore detailStore,
        DownloadService downloadService,
        ObjectMapper objectMapper,
        ZentaoHtmlParser htmlParser
    ) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String normalizedUrl = baseUrl.trim().replaceFirst("/+$", "");
        if (normalizedUrl.isBlank()) {
            throw new IllegalArgumentException("禅道服务器地址不能为空");
        }
        URI.create(normalizedUrl);
        this.baseUrl = normalizedUrl;
        this.detailStore = Objects.requireNonNull(detailStore, "detailStore");
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.htmlParser = Objects.requireNonNull(htmlParser, "htmlParser");
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookieManager)
            .build();
    }

    public boolean login(String account, String password) {
        if (isBlank(account) || isBlank(password)) {
            return false;
        }
        synchronized (authenticationLock) {
            return loginInternal(account, password);
        }
    }

    public TaskPageResult fetchMyTasks(int page, int pageSize) throws IOException, InterruptedException {
        validatePage(page, pageSize);
        String url;
        if (page == 1) {
            url = baseUrl + "/my-task.json";
        } else {
            Integer total = cachedTaskTotal;
            if (total == null) {
                fetchMyTasks(1, pageSize);
                total = cachedTaskTotal;
            }
            url = baseUrl + "/my-task-assignedTo-id_desc-" + (total == null ? 100 : total)
                + "-" + pageSize + "-" + page + ".json";
        }

        JsonNode data = parseDataNode(sendTextWithAutoReLogin("GET", url, null));
        List<TaskInfo> tasks = new ArrayList<>();
        JsonNode taskNodes = data.path("tasks");
        if (taskNodes.isArray()) {
            for (JsonNode task : taskNodes) {
                String id = textValue(task, "id");
                tasks.add(new TaskInfo(
                    id,
                    textValue(task, "projectName"),
                    textValue(task, "story"),
                    textValue(task, "storyTitle"),
                    textValue(task, "storyStatus"),
                    textValue(task, "name"),
                    textValue(task, "status"),
                    textValue(task, "openedBy"),
                    textValue(task, "assignedTo"),
                    textValue(task, "deadline"),
                    textValue(task, "realStarted"),
                    baseUrl + "/task-view-" + id + ".html"
                ));
            }
        }

        JsonNode pager = data.path("pager");
        int recTotal = positiveOrDefault(pager.path("recTotal"), tasks.size());
        int pageTotal = positiveOrDefault(pager.path("pageTotal"), 1);
        if (page == 1) {
            cachedTaskTotal = recTotal;
        }
        return new TaskPageResult(List.copyOf(tasks), recTotal, pageSize, pageTotal, page);
    }

    public TaskDetail fetchTaskDetail(String taskId) throws IOException, InterruptedException {
        requireId(taskId, "任务ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/task-view-" + taskId + ".html", null);
        TaskDetail detail = htmlParser.parseTask(taskId, baseUrl, html);

        Path detailDir = detailStore.saveTask(detail);
        scheduleDownloads("task", taskId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    public BugPageResult fetchMyBugs(int page, int pageSize) throws IOException, InterruptedException {
        validatePage(page, pageSize);
        String url;
        if (page == 1) {
            url = baseUrl + "/my-bug.json";
        } else {
            Integer total = cachedBugTotal;
            if (total == null) {
                fetchMyBugs(1, pageSize);
                total = cachedBugTotal;
            }
            url = baseUrl + "/my-bug-assignedTo-id_desc-" + (total == null ? 100 : total)
                + "-" + pageSize + "-" + page + ".json";
        }

        JsonNode data = parseDataNode(sendTextWithAutoReLogin("GET", url, null));
        List<BugInfo> bugs = new ArrayList<>();
        JsonNode bugNodes = data.path("bugs");
        if (bugNodes.isArray()) {
            for (JsonNode bug : bugNodes) {
                String id = textValue(bug, "id");
                bugs.add(new BugInfo(
                    id,
                    textValue(bug, "title"),
                    textValue(bug, "status"),
                    textValue(bug, "severity"),
                    textValue(bug, "pri"),
                    textValue(bug, "openedBy"),
                    textValue(bug, "openedDate"),
                    textValue(bug, "assignedTo"),
                    textValue(bug, "projectName"),
                    baseUrl + "/bug-view-" + id + ".html"
                ));
            }
        }

        JsonNode pager = data.path("pager");
        int recTotal = positiveOrDefault(pager.path("recTotal"), bugs.size());
        int pageTotal = positiveOrDefault(pager.path("pageTotal"), 1);
        if (page == 1) {
            cachedBugTotal = recTotal;
        }
        return new BugPageResult(List.copyOf(bugs), recTotal, pageSize, pageTotal, page);
    }

    public StoryDetail fetchStoryDetail(String storyId) throws IOException, InterruptedException {
        requireId(storyId, "需求ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/story-view-" + storyId + ".html", null);
        StoryDetail detail = htmlParser.parseStory(storyId, baseUrl, html);
        Path detailDir = detailStore.saveStory(detail);
        scheduleDownloads("story", storyId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    public BugDetail fetchBugDetail(String bugId) throws IOException, InterruptedException {
        requireId(bugId, "Bug ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/bug-view-" + bugId + ".html", null);
        BugDetail detail = htmlParser.parseBug(bugId, baseUrl, html);
        Path detailDir = detailStore.saveBug(detail);
        scheduleDownloads("bug", bugId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    public boolean finishTask(String taskId, String finishedDate, double consumed) {
        requireId(taskId, "任务ID");
        requireLoggedIn();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("assignedTo", loggedInAccount);
        // 旧版完成表单拒绝严格零值，对外仍保留调用方传入的实际工时。
        form.put("currentConsumed", formatNumber(consumed == 0.0d ? 0.01d : consumed));
        form.put("consumed", "0");
        form.put("finishedDate", finishedDate);
        form.put("status", "done");
        try {
            String responseBody = sendTextWithAutoReLogin(
                "POST",
                baseUrl + "/task-finish-" + taskId + ".html",
                form
            );
            WriteVerdict verdict = classifyWriteResponse(responseBody);
            return verdict == WriteVerdict.SUCCESS
                || (verdict == WriteVerdict.UNKNOWN && verifyTaskDone(taskId));
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public boolean resolveBug(String bugId, String resolution) {
        requireId(bugId, "Bug ID");
        requireLoggedIn();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("resolution", resolution);
        form.put("resolvedBuild", "trunk");
        form.put("resolvedDate", currentUtcDateTime());
        form.put("assignedTo", loggedInAccount);
        form.put("status", "resolved");
        try {
            String responseBody = sendTextWithAutoReLogin(
                "POST",
                baseUrl + "/bug-resolve-" + bugId + ".json",
                form
            );
            WriteVerdict verdict = classifyWriteResponse(responseBody);
            return verdict == WriteVerdict.SUCCESS
                || (verdict == WriteVerdict.UNKNOWN && verifyBugResolved(bugId));
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public static String currentUtcDateTime() {
        return ZENTAO_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    private boolean loginInternal(String account, String password) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("account", account);
        form.put("password", password);
        try {
            HttpResponse<byte[]> response = sendRaw("POST", baseUrl + "/user-login.json", form);
            if (response.statusCode() != 200) {
                return false;
            }

            JsonNode result = objectMapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
            // 不同 12.x 部署可能把登录 JSON 重复编码为字符串。
            for (int depth = 0; depth < 2 && result != null && result.isString(); depth++) {
                result = objectMapper.readTree(result.stringValue());
            }
            if (result != null && result.isObject() && result.get("status") == null) {
                JsonNode data = result.get("data");
                if (data != null && data.isString()) {
                    data = objectMapper.readTree(data.stringValue());
                }
                if (data != null && data.isObject()) {
                    result = data;
                }
            }
            if (result != null && "success".equals(result.path("status").stringValue(""))) {
                loggedInAccount = account;
                savedPassword = password;
                sessionGeneration.incrementAndGet();
                cachedTaskTotal = null;
                cachedBugTotal = null;
                return true;
            }
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private String sendTextWithAutoReLogin(String method, String url, Map<String, String> form)
        throws IOException, InterruptedException {
        return new String(sendBytesWithAutoReLogin(method, url, form, true), StandardCharsets.UTF_8);
    }

    private byte[] sendBytesWithAutoReLogin(
        String method,
        String url,
        Map<String, String> form,
        boolean allowRetry
    ) throws IOException, InterruptedException {
        // 登录页常以 HTTP 200 返回；检测到后只允许刷新会话并重放一次。
        long generationBeforeRequest = sessionGeneration.get();
        HttpResponse<byte[]> response = sendRaw(method, url, form);
        ensureSuccessfulStatus(response, url);
        byte[] body = response.body();
        if (containsLoginPage(body)) {
            if (!allowRetry || !reAuthenticate(generationBeforeRequest)) {
                throw new IOException("Token 已过期，自动重新登录失败");
            }
            return sendBytesWithAutoReLogin(method, url, form, false);
        }
        return body;
    }

    private boolean reAuthenticate(long generationBeforeRequest) {
        synchronized (authenticationLock) {
            if (sessionGeneration.get() != generationBeforeRequest) {
                // 其他线程已经刷新成功，当前请求直接复用新 Cookie。
                return true;
            }
            String account = loggedInAccount;
            String password = savedPassword;
            return !isBlank(account) && !isBlank(password) && loginInternal(account, password);
        }
    }

    private HttpResponse<byte[]> sendRaw(String method, String url, Map<String, String> form)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "*/*");

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8));
        } else {
            builder.header("Content-Type", "application/json")
                .GET();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void scheduleDownloads(
        String category,
        String id,
        List<String> images,
        List<AttachmentInfo> attachments,
        Path detailDir
    ) throws IOException {
        Path imageDir = detailStore.imageDirectory(detailDir);
        Path attachmentDir = detailStore.attachmentDirectory(detailDir);

        for (int index = 0; index < images.size(); index++) {
            String imageUrl = images.get(index);
            String prefix = switch (category) {
                case "task" -> "task_" + id;
                case "bug" -> "bug_" + id;
                default -> id;
            };
            String fileName = prefix + "_img_" + (index + 1) + extensionOf(imageUrl);
            scheduleDownload(imageUrl, imageDir, fileName, "image-" + (index + 1));
        }

        List<String> attachmentNames = new ArrayList<>(attachments.size());
        List<String> attachmentIdentifiers = new ArrayList<>(attachments.size());
        List<String> attachmentFallbacks = new ArrayList<>(attachments.size());
        Map<String, Integer> attachmentNameCounts = new LinkedHashMap<>();
        for (int index = 0; index < attachments.size(); index++) {
            AttachmentInfo attachment = attachments.get(index);
            String identifier = isBlank(attachment.id()) ? Integer.toString(index + 1) : attachment.id();
            String fallback = "attachment-" + FileNames.sanitize(identifier, Integer.toString(index + 1));
            String fileName = FileNames.sanitize(attachment.name(), fallback);
            attachmentNames.add(fileName);
            attachmentIdentifiers.add(identifier);
            attachmentFallbacks.add(fallback);
            attachmentNameCounts.merge(fileName.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        Set<String> reservedAttachmentNames = new HashSet<>();
        for (int index = 0; index < attachments.size(); index++) {
            AttachmentInfo attachment = attachments.get(index);
            String identifier = attachmentIdentifiers.get(index);
            String fallback = attachmentFallbacks.get(index);
            String candidate = attachmentNames.get(index);
            if (attachmentNameCounts.get(candidate.toLowerCase(Locale.ROOT)) > 1) {
                candidate = FileNames.addSuffix(candidate, identifier);
            }
            String fileName = reserveUniqueFileName(
                candidate,
                identifier,
                reservedAttachmentNames
            );
            scheduleDownload(attachment.url(), attachmentDir, fileName, fallback);
        }
    }

    private void scheduleDownload(String url, Path directory, String fileName, String fallback) {
        downloadService.submit(url, () -> {
            Path target = detailStore.safeFile(directory, fileName, fallback);
            downloadToFileWithAutoReLogin(url, target);
        });
    }

    // 兼容根节点、data 节点以及二者被再次编码为 JSON 字符串的返回形式。
    private JsonNode parseDataNode(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        if (root == null) {
            throw new IOException("禅道返回了空 JSON");
        }
        if (root.isString()) {
            root = objectMapper.readTree(root.stringValue());
        }
        if (root == null || !root.isObject()) {
            throw new IOException("禅道返回的 JSON 结构无效");
        }
        JsonNode data = root.get("data");
        if (data == null) {
            return root;
        }
        if (data.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (data.isString()) {
            try {
                JsonNode parsed = objectMapper.readTree(data.stringValue());
                return parsed == null ? objectMapper.createObjectNode() : parsed;
            } catch (Exception exception) {
                throw new IOException("禅道 data 字段不是有效 JSON", exception);
            }
        }
        return data;
    }

    private void downloadToFileWithAutoReLogin(String url, Path target)
        throws IOException, InterruptedException {
        // 先写同目录临时文件，完整成功后再原子替换目标。
        Path temporary = AtomicFiles.createTemporarySibling(target);
        try {
            sendFileWithAutoReLogin(url, temporary, true);
            AtomicFiles.replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void sendFileWithAutoReLogin(String url, Path target, boolean allowRetry)
        throws IOException, InterruptedException {
        long generationBeforeRequest = sessionGeneration.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "*/*")
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        );
        ensureSuccessfulStatus(response, url);
        // 下载端点也可能以 HTTP 200 返回登录 HTML，必须检查文件内容。
        if (containsLoginPage(target)) {
            if (!allowRetry || !reAuthenticate(generationBeforeRequest)) {
                throw new IOException("Token 已过期，自动重新登录失败");
            }
            sendFileWithAutoReLogin(url, target, false);
        }
    }

    // 写接口跨版本返回 JSON、双重编码 JSON 或 HTML；不明确时由调用方回查状态。
    private WriteVerdict classifyWriteResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return WriteVerdict.UNKNOWN;
        }
        String normalized = responseBody.stripLeading();
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1).stripLeading();
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("user-deny")
            || lower.contains("changepassword")
            || lower.contains("access denied")) {
            return WriteVerdict.FAILURE;
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            for (int depth = 0; depth < 2 && root != null && root.isString(); depth++) {
                root = objectMapper.readTree(root.stringValue());
            }
            if (root != null && root.isObject()) {
                WriteVerdict result = classifyWriteToken(root.get("result"));
                WriteVerdict status = classifyWriteToken(root.get("status"));
                WriteVerdict success = classifyWriteToken(root.get("success"));
                if (result == WriteVerdict.FAILURE
                    || status == WriteVerdict.FAILURE
                    || success == WriteVerdict.FAILURE) {
                    return WriteVerdict.FAILURE;
                }
                if (result == WriteVerdict.SUCCESS
                    || status == WriteVerdict.SUCCESS
                    || success == WriteVerdict.SUCCESS) {
                    return WriteVerdict.SUCCESS;
                }
                JsonNode errors = root.get("errors");
                if (errors != null && !errors.isNull() && errors.size() > 0) {
                    return WriteVerdict.FAILURE;
                }
            }
        } catch (Exception ignored) {
        }
        return WriteVerdict.UNKNOWN;
    }

    private static WriteVerdict classifyWriteToken(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return WriteVerdict.UNKNOWN;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? WriteVerdict.SUCCESS : WriteVerdict.FAILURE;
        }
        if (node.isNumber()) {
            int value = node.intValue();
            if (value == 1) {
                return WriteVerdict.SUCCESS;
            }
            if (value == 0) {
                return WriteVerdict.FAILURE;
            }
            return WriteVerdict.UNKNOWN;
        }
        String value = node.stringValue("").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "success", "ok", "true", "1" -> WriteVerdict.SUCCESS;
            case "fail", "failed", "error", "false", "0" -> WriteVerdict.FAILURE;
            default -> WriteVerdict.UNKNOWN;
        };
    }

    private boolean verifyTaskDone(String taskId) throws IOException, InterruptedException {
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/task-view-" + taskId + ".html", null);
        String status = htmlParser.parseTask(taskId, baseUrl, html).status();
        return statusEquals(status, "done", "已完成");
    }

    private boolean verifyBugResolved(String bugId) throws IOException, InterruptedException {
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/bug-view-" + bugId + ".html", null);
        String status = htmlParser.parseBug(bugId, baseUrl, html).status();
        return statusEquals(status, "resolved", "已解决");
    }

    private static boolean statusEquals(String actual, String english, String chinese) {
        String normalized = actual == null ? "" : actual.trim();
        return english.equalsIgnoreCase(normalized) || chinese.equals(normalized);
    }

    private static String reserveUniqueFileName(
        String candidate,
        String identifier,
        Set<String> reservedNames
    ) {
        String normalizedKey = candidate.toLowerCase(Locale.ROOT);
        if (reservedNames.add(normalizedKey)) {
            return candidate;
        }

        String suffix = FileNames.sanitize(identifier, "duplicate");
        int sequence = 1;
        while (true) {
            String numberedSuffix = sequence == 1 ? suffix : suffix + "-" + sequence;
            String unique = FileNames.addSuffix(candidate, numberedSuffix);
            if (reservedNames.add(unique.toLowerCase(Locale.ROOT))) {
                return unique;
            }
            sequence++;
        }
    }

    private enum WriteVerdict {
        SUCCESS,
        FAILURE,
        UNKNOWN
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isString() ? value.stringValue("") : value.toString();
    }

    private static int positiveOrDefault(JsonNode node, int fallback) {
        int value = node == null ? 0 : node.asInt(0);
        return value > 0 ? value : fallback;
    }

    private static void ensureSuccessfulStatus(HttpResponse<?> response, String url) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("禅道请求失败 (HTTP " + status + "): " + url);
        }
    }

    private static boolean containsLoginPage(byte[] body) {
        return body != null && new String(body, StandardCharsets.UTF_8).contains(LOGIN_PAGE_INDICATOR);
    }

    private static boolean containsLoginPage(Path file) throws IOException {
        byte[] indicator = LOGIN_PAGE_INDICATOR.getBytes(StandardCharsets.UTF_8);
        int matched = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int value;
            while ((value = input.read()) >= 0) {
                if ((byte) value == indicator[matched]) {
                    matched++;
                    if (matched == indicator.length) {
                        return true;
                    }
                } else {
                    matched = (byte) value == indicator[0] ? 1 : 0;
                }
            }
        }
        return false;
    }

    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        return form.entrySet().stream()
            .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String extensionOf(String url) {
        try {
            String path = URI.create(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String extension = name.substring(dot);
                Matcher matcher = SAFE_EXTENSION.matcher(extension);
                if (matcher.matches()) {
                    return extension;
                }
            }
        } catch (Exception ignored) {
        }
        return ".png";
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("页码和每页条数必须大于 0");
        }
    }

    private static void requireId(String id, String label) {
        if (isBlank(id)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new IllegalArgumentException(label + "格式无效");
        }
    }

    private void requireLoggedIn() {
        if (isBlank(loggedInAccount)) {
            throw new IllegalStateException("未登录");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
