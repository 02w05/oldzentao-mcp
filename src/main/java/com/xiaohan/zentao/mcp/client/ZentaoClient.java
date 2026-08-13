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

/**
 * 封装本 MCP 服务使用的禅道 HTTP 路由和 Cookie 登录会话。
 *
 * <p>客户端负责登录、任务与 Bug 分页查询、详情页解析、详情落盘，以及任务完成和
 * Bug 解决操作。详情文本同步保存后，图片与附件会提交到共享下载服务，不阻塞工具
 * 响应。</p>
 *
 * <p>禅道会话失效时通常返回登录页而不是明确的 401。本类通过响应内容识别这种
 * 情况，使用保存的凭据在锁内重新登录，并将原请求最多重试一次。会话代数用于避免
 * 多个并发请求同时重复登录。</p>
 */
public final class ZentaoClient {
    /** 连接、单次请求和下载统一使用的超时时间。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 禅道登录页 URL/HTML 中用于识别会话失效的稳定片段。 */
    private static final String LOGIN_PAGE_INDICATOR = "user-login";
    private static final DateTimeFormatter ZENTAO_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 只接受短的字母数字扩展名，避免把 URL 路径尾部当作任意文件名使用。 */
    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,10}$");

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ZentaoHtmlParser htmlParser;
    private final DetailStore detailStore;
    private final DownloadService downloadService;
    // 登录和自动重登录共用一把锁，保证 Cookie 与凭据状态同步更新。
    private final Object authenticationLock = new Object();
    // 每次成功登录递增，用于判断其他线程是否已经替当前请求刷新了会话。
    private final AtomicLong sessionGeneration = new AtomicLong();

    // 成功登录后保存账号和密码，供会话失效时执行一次自动重登录。
    private volatile String loggedInAccount;
    private volatile String savedPassword;
    // 禅道非首页分页路由需要总记录数，首页响应会把它缓存下来。
    private volatile Integer cachedTaskTotal;
    private volatile Integer cachedBugTotal;

    /**
     * 使用默认 JSON 映射器和 HTML 解析器创建站点客户端。
     *
     * @param baseUrl 禅道站点根地址
     * @param detailStore 详情同步存储服务
     * @param downloadService 图片和附件后台下载服务
     */
    public ZentaoClient(
        String baseUrl,
        DetailStore detailStore,
        DownloadService downloadService
    ) {
        this(baseUrl, detailStore, downloadService, JsonMapper.builder().build(), new ZentaoHtmlParser());
    }

    /** 使用可替换的解析依赖创建客户端，供同包代码验证不同响应场景。 */
    ZentaoClient(
        String baseUrl,
        DetailStore detailStore,
        DownloadService downloadService,
        ObjectMapper objectMapper,
        ZentaoHtmlParser htmlParser
    ) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        // 后续路由统一自行添加斜杠，先移除根地址结尾的重复斜杠。
        String normalizedUrl = baseUrl.trim().replaceFirst("/+$", "");
        if (normalizedUrl.isBlank()) {
            throw new IllegalArgumentException("禅道服务器地址不能为空");
        }
        // 构造阶段立即验证 URI 语法，避免首次工具调用时才暴露地址错误。
        URI.create(normalizedUrl);

        this.baseUrl = normalizedUrl;
        this.detailStore = Objects.requireNonNull(detailStore, "detailStore");
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.htmlParser = Objects.requireNonNull(htmlParser, "htmlParser");

        // 同一个 HttpClient 持有 CookieManager，使登录响应中的会话 Cookie 自动用于后续请求。
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookieManager)
            .build();
    }

    /**
     * 使用账号密码建立禅道 Cookie 会话。
     *
     * @param account 登录账号
     * @param password 登录密码
     * @return 服务端明确返回登录成功时为 {@code true}，参数无效或请求失败时为 {@code false}
     */
    public boolean login(String account, String password) {
        if (isBlank(account) || isBlank(password)) {
            return false;
        }
        synchronized (authenticationLock) {
            return loginInternal(account, password);
        }
    }

    /**
     * 获取当前用户的一页任务摘要。
     *
     * <p>禅道第一页使用简短路由，后续页路由中必须包含总记录数。因此未缓存总数时，
     * 方法会先请求第一页建立分页基准。</p>
     *
     * @param page 从 1 开始的页码
     * @param pageSize 每页记录数
     * @return 标准化后的任务及分页信息
     * @throws IOException 网络、HTTP 状态或 JSON 响应无效时抛出
     * @throws InterruptedException 请求线程被中断时抛出
     */
    public TaskPageResult fetchMyTasks(int page, int pageSize) throws IOException, InterruptedException {
        validatePage(page, pageSize);
        String url;
        if (page == 1) {
            url = baseUrl + "/my-task.json";
        } else {
            Integer total = cachedTaskTotal;
            if (total == null) {
                // 后续页 URL 依赖总数；递归只会进入固定的第一页分支，不会无限递归。
                fetchMyTasks(1, pageSize);
                total = cachedTaskTotal;
            }
            url = baseUrl + "/my-task-assignedTo-id_desc-" + (total == null ? 100 : total)
                + "-" + pageSize + "-" + page + ".json";
        }

        // 先统一拆解禅道可能双重编码的 data，再映射稳定模型字段。
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

        // 部分页响应缺少完整 pager，使用本页大小和单页作为保守默认值。
        JsonNode pager = data.path("pager");
        int recTotal = positiveOrDefault(pager.path("recTotal"), tasks.size());
        int pageTotal = positiveOrDefault(pager.path("pageTotal"), 1);
        if (page == 1) {
            cachedTaskTotal = recTotal;
        }
        return new TaskPageResult(List.copyOf(tasks), recTotal, pageSize, pageTotal, page);
    }

    /**
     * 获取、解析并保存任务详情，然后安排页面资源的后台下载。
     *
     * @param taskId 任务 ID
     * @return 已解析的任务详情
     * @throws IOException 网络、解析后的本地写入或目录校验失败时抛出
     * @throws InterruptedException 请求线程被中断时抛出
     */
    public TaskDetail fetchTaskDetail(String taskId) throws IOException, InterruptedException {
        requireId(taskId, "任务ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/task-view-" + taskId + ".html", null);
        TaskDetail detail = htmlParser.parseTask(taskId, baseUrl, html);
        // 同步文件必须先成功，之后才提交引用这些目录的异步下载任务。
        Path detailDir = detailStore.saveTask(detail);
        scheduleDownloads("task", taskId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    /**
     * 获取当前用户的一页 Bug 摘要。
     *
     * <p>分页路由和总数缓存策略与任务列表相同。</p>
     *
     * @param page 从 1 开始的页码
     * @param pageSize 每页记录数
     * @return 标准化后的 Bug 及分页信息
     * @throws IOException 网络、HTTP 状态或 JSON 响应无效时抛出
     * @throws InterruptedException 请求线程被中断时抛出
     */
    public BugPageResult fetchMyBugs(int page, int pageSize) throws IOException, InterruptedException {
        validatePage(page, pageSize);
        String url;
        if (page == 1) {
            url = baseUrl + "/my-bug.json";
        } else {
            Integer total = cachedBugTotal;
            if (total == null) {
                // 先取首页得到构造后续分页路由所需的总记录数。
                fetchMyBugs(1, pageSize);
                total = cachedBugTotal;
            }
            url = baseUrl + "/my-bug-assignedTo-id_desc-" + (total == null ? 100 : total)
                + "-" + pageSize + "-" + page + ".json";
        }

        // 将禅道原始字段映射为只读摘要，详情页 URL 则由当前根地址和 ID 构造。
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

    /**
     * 获取、解析并保存需求详情，然后安排页面资源的后台下载。
     *
     * @param storyId 需求 ID
     * @return 已解析的需求详情
     * @throws IOException 网络或本地持久化失败时抛出
     * @throws InterruptedException 请求线程被中断时抛出
     */
    public StoryDetail fetchStoryDetail(String storyId) throws IOException, InterruptedException {
        requireId(storyId, "需求ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/story-view-" + storyId + ".html", null);
        StoryDetail detail = htmlParser.parseStory(storyId, baseUrl, html);
        Path detailDir = detailStore.saveStory(detail);
        scheduleDownloads("story", storyId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    /**
     * 获取、解析并保存 Bug 详情，然后安排页面资源的后台下载。
     *
     * @param bugId Bug ID
     * @return 已解析的 Bug 详情
     * @throws IOException 网络或本地持久化失败时抛出
     * @throws InterruptedException 请求线程被中断时抛出
     */
    public BugDetail fetchBugDetail(String bugId) throws IOException, InterruptedException {
        requireId(bugId, "Bug ID");
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/bug-view-" + bugId + ".html", null);
        BugDetail detail = htmlParser.parseBug(bugId, baseUrl, html);
        Path detailDir = detailStore.saveBug(detail);
        scheduleDownloads("bug", bugId, detail.images(), detail.attachments(), detailDir);
        return detail;
    }

    /**
     * 提交任务完成表单，并在响应语义不明确时回查任务状态。
     *
     * @param taskId 任务 ID
     * @param finishedDate 禅道格式的完成时间
     * @param consumed 本次填写的消耗工时
     * @return 已确认完成时返回 {@code true}；请求、响应或回查失败时返回 {@code false}
     */
    public boolean finishTask(String taskId, String finishedDate, double consumed) {
        requireId(taskId, "任务ID");
        requireLoggedIn();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("assignedTo", loggedInAccount);
        // 禅道完成表单不接受严格的零值，外部显示仍保留调用方传入的实际工时。
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
            // 某些版本返回 JSON，另一些版本重定向到 HTML；无法直接判断时回查详情状态。
            WriteVerdict verdict = classifyWriteResponse(responseBody);
            return verdict == WriteVerdict.SUCCESS
                || (verdict == WriteVerdict.UNKNOWN && verifyTaskDone(taskId));
        } catch (Exception exception) {
            // 对外契约使用布尔结果，但不能吞掉线程中断语义。
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 提交 Bug 解决表单，并在响应语义不明确时回查 Bug 状态。
     *
     * @param bugId Bug ID
     * @param resolution 禅道接受的解决方案代码
     * @return 已确认解决时返回 {@code true}；请求、响应或回查失败时返回 {@code false}
     */
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
            // 与任务完成相同，UNKNOWN 只表示响应不可判定，仍可通过详情状态确认成功。
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

    /** @return 构造客户端时规范化后的禅道根地址 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 生成禅道表单使用的当前 UTC 日期时间。
     *
     * @return 格式为 {@code yyyy-MM-dd HH:mm:ss} 的 UTC 时间文本
     */
    public static String currentUtcDateTime() {
        return ZENTAO_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 执行实际登录请求并更新会话状态；调用方必须持有 {@code authenticationLock}。
     */
    private boolean loginInternal(String account, String password) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("account", account);
        form.put("password", password);
        try {
            HttpResponse<byte[]> response = sendRaw("POST", baseUrl + "/user-login.json", form);
            if (response.statusCode() != 200) {
                return false;
            }
            // 禅道不同版本可能把 JSON 再编码成字符串，最多解包两层以兼容这些响应。
            JsonNode result = objectMapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
            for (int depth = 0; depth < 2 && result != null && result.isString(); depth++) {
                result = objectMapper.readTree(result.stringValue());
            }
            if (result != null && result.isObject() && result.get("status") == null) {
                // 部分接口把真正的登录结果放在 data 中，data 本身也可能是 JSON 字符串。
                JsonNode data = result.get("data");
                if (data != null && data.isString()) {
                    data = objectMapper.readTree(data.stringValue());
                }
                if (data != null && data.isObject()) {
                    result = data;
                }
            }
            if (result != null && "success".equals(result.path("status").stringValue(""))) {
                // 先保存自动重登录所需凭据，再递增代数发布新会话并清空账号相关分页缓存。
                loggedInAccount = account;
                savedPassword = password;
                sessionGeneration.incrementAndGet();
                cachedTaskTotal = null;
                cachedBugTotal = null;
                return true;
            }
            return false;
        } catch (InterruptedException exception) {
            // 布尔登录 API 不向外抛受检异常，但必须恢复中断标记供上层感知。
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    /** 发送允许自动重登录的请求，并将响应体按 UTF-8 解码为文本。 */
    private String sendTextWithAutoReLogin(String method, String url, Map<String, String> form)
        throws IOException, InterruptedException {
        return new String(sendBytesWithAutoReLogin(method, url, form, true), StandardCharsets.UTF_8);
    }

    /**
     * 发送请求并检测登录页；会话过期时重新认证，并将原请求最多重放一次。
     */
    private byte[] sendBytesWithAutoReLogin(
        String method,
        String url,
        Map<String, String> form,
        boolean allowRetry
    ) throws IOException, InterruptedException {
        // 记录请求开始时的会话版本，用来识别其他并发请求是否已完成刷新。
        long generationBeforeRequest = sessionGeneration.get();
        HttpResponse<byte[]> response = sendRaw(method, url, form);
        ensureSuccessfulStatus(response, url);
        byte[] body = response.body();
        if (containsLoginPage(body)) {
            // allowRetry=false 的重放若仍返回登录页，立即失败，避免递归重试循环。
            if (!allowRetry || !reAuthenticate(generationBeforeRequest)) {
                throw new IOException("Token 已过期，自动重新登录失败");
            }
            return sendBytesWithAutoReLogin(method, url, form, false);
        }
        return body;
    }

    /**
     * 串行化自动重登录；若会话代数已经变化，直接复用其他线程刷新的会话。
     */
    private boolean reAuthenticate(long generationBeforeRequest) {
        synchronized (authenticationLock) {
            if (sessionGeneration.get() != generationBeforeRequest) {
                // 另一个请求已经成功登录，当前请求只需使用新 Cookie 重试。
                return true;
            }
            String account = loggedInAccount;
            String password = savedPassword;
            return !isBlank(account) && !isBlank(password) && loginInternal(account, password);
        }
    }

    /**
     * 构造并同步发送一条底层 HTTP 请求，不进行业务响应解析或自动重试。
     */
    private HttpResponse<byte[]> sendRaw(String method, String url, Map<String, String> form)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "*/*");

        if ("POST".equals(method)) {
            // 禅道写接口和登录接口使用传统表单编码，而不是 JSON 请求体。
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8));
        } else {
            builder.header("Content-Type", "application/json")
                .GET();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 为详情中的图片和附件生成稳定、安全且互不冲突的本地名称，并提交下载任务。
     */
    private void scheduleDownloads(
        String category,
        String id,
        List<String> images,
        List<AttachmentInfo> attachments,
        Path detailDir
    ) throws IOException {
        Path imageDir = detailStore.imageDirectory(detailDir);
        Path attachmentDir = detailStore.attachmentDirectory(detailDir);

        // 图片不使用远端文件名，按详情类别、ID 和页面顺序生成可预测名称。
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

        // 第一遍统一清理名称并统计大小写不敏感的重复项，符合 Windows 文件名语义。
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

        // 第二遍为重名项添加附件 ID；若 ID 也重复，再递增序号直到本批次唯一。
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

    /**
     * 提交单个下载动作；实际执行前再次从真实目录解析安全目标路径。
     */
    private void scheduleDownload(String url, Path directory, String fileName, String fallback) {
        downloadService.submit(url, () -> {
            Path target = detailStore.safeFile(directory, fileName, fallback);
            downloadToFileWithAutoReLogin(url, target);
        });
    }

    /**
     * 将禅道列表响应归一为实际数据节点。
     *
     * <p>兼容根节点直接为对象、根节点为 JSON 字符串、{@code data} 为对象，
     * 以及 {@code data} 再次包含 JSON 字符串的几种返回形式。</p>
     */
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
            // 没有 data 包装时，根对象本身就是业务数据。
            return root;
        }
        if (data.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (data.isString()) {
            // 对格式错误的内层 JSON 给出比通用解析异常更明确的提示。
            try {
                JsonNode parsed = objectMapper.readTree(data.stringValue());
                return parsed == null ? objectMapper.createObjectNode() : parsed;
            } catch (Exception exception) {
                throw new IOException("禅道 data 字段不是有效 JSON", exception);
            }
        }
        return data;
    }

    /**
     * 下载到同目录临时文件，完整成功后再原子替换最终目标。
     */
    private void downloadToFileWithAutoReLogin(String url, Path target)
        throws IOException, InterruptedException {
        Path temporary = AtomicFiles.createTemporarySibling(target);
        try {
            sendFileWithAutoReLogin(url, temporary, true);
            AtomicFiles.replace(temporary, target);
        } finally {
            // 下载、重登录或替换任一步骤失败时都不保留不完整临时文件。
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 直接把响应体写入文件；若写入的其实是登录页，则刷新会话并覆盖重试一次。
     */
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
        // 下载接口也可能以 HTTP 200 返回登录 HTML，因此需要检查实际文件内容。
        if (containsLoginPage(target)) {
            if (!allowRetry || !reAuthenticate(generationBeforeRequest)) {
                throw new IOException("Token 已过期，自动重新登录失败");
            }
            sendFileWithAutoReLogin(url, target, false);
        }
    }

    /**
     * 判断写操作响应是否明确表示成功或失败。
     *
     * <p>禅道版本之间可能返回布尔值、数字、状态字符串、双重编码 JSON，甚至重定向
     * 后的 HTML。无法从响应本身确认时返回 {@link WriteVerdict#UNKNOWN}，由调用方
     * 查询详情页核实最终状态。</p>
     */
    private WriteVerdict classifyWriteResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return WriteVerdict.UNKNOWN;
        }
        String normalized = responseBody.stripLeading();
        // 某些部署在 UTF-8 文本前带 BOM，移除后再判断 JSON 或 HTML 内容。
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1).stripLeading();
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        // 权限拒绝或强制改密页面虽然是 HTML，也可直接认定操作失败。
        if (lower.contains("user-deny")
            || lower.contains("changepassword")
            || lower.contains("access denied")) {
            return WriteVerdict.FAILURE;
        }
        try {
            // 与登录响应相同，写接口结果可能被当作 JSON 字符串重复编码。
            JsonNode root = objectMapper.readTree(normalized);
            for (int depth = 0; depth < 2 && root != null && root.isString(); depth++) {
                root = objectMapper.readTree(root.stringValue());
            }
            if (root != null && root.isObject()) {
                // 兼容常见的 result、status、success 字段；任何明确失败优先于成功字段。
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
                // 即使没有状态字段，非空 errors 也足以确认失败。
                JsonNode errors = root.get("errors");
                if (errors != null && !errors.isNull() && errors.size() > 0) {
                    return WriteVerdict.FAILURE;
                }
            }
        } catch (Exception ignored) {
            // 禅道写入成功后经常返回重定向后的 HTML 详情页，无法解析 JSON 并不等于失败。
        }
        return WriteVerdict.UNKNOWN;
    }

    /** 将单个 JSON 标量归一为三态写操作结论。 */
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

    /** 写响应不可判定时重新读取任务详情，确认状态是否已经变为完成。 */
    private boolean verifyTaskDone(String taskId) throws IOException, InterruptedException {
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/task-view-" + taskId + ".html", null);
        String status = htmlParser.parseTask(taskId, baseUrl, html).status();
        return statusEquals(status, "done", "已完成");
    }

    /** 写响应不可判定时重新读取 Bug 详情，确认状态是否已经变为已解决。 */
    private boolean verifyBugResolved(String bugId) throws IOException, InterruptedException {
        String html = sendTextWithAutoReLogin("GET", baseUrl + "/bug-view-" + bugId + ".html", null);
        String status = htmlParser.parseBug(bugId, baseUrl, html).status();
        return statusEquals(status, "resolved", "已解决");
    }

    /** 同时接受禅道可能返回的英文状态代码和中文展示文本。 */
    private static boolean statusEquals(String actual, String english, String chinese) {
        String normalized = actual == null ? "" : actual.trim();
        return english.equalsIgnoreCase(normalized) || chinese.equals(normalized);
    }

    /**
     * 在当前详情下载批次内预留一个大小写不敏感的唯一附件名。
     *
     * <p>附件 ID 仍冲突时继续追加序号，循环直到名称成功加入预留集合。</p>
     */
    private static String reserveUniqueFileName(
        String candidate,
        String identifier,
        Set<String> reservedNames
    ) {
        String normalizedKey = candidate.toLowerCase(Locale.ROOT);
        if (reservedNames.add(normalizedKey)) {
            return candidate;
        }

        // 标识也按文件名规则清理，避免为了解决重名重新引入非法字符。
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

    /** 写接口响应的三态判断，UNKNOWN 表示需要读取详情进行最终确认。 */
    private enum WriteVerdict {
        /** 响应明确表示写入成功。 */
        SUCCESS,
        /** 响应明确表示写入失败。 */
        FAILURE,
        /** 响应格式无法可靠判断结果。 */
        UNKNOWN
    }

    /** 从 JSON 对象读取文本字段，缺失值为空串，非字符串值保留其 JSON 文本。 */
    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isString() ? value.stringValue("") : value.toString();
    }

    /** 读取正整数分页字段，缺失、零或负数时使用调用方给出的默认值。 */
    private static int positiveOrDefault(JsonNode node, int fallback) {
        int value = node == null ? 0 : node.asInt(0);
        return value > 0 ? value : fallback;
    }

    /** 确认响应状态属于 2xx，否则附带请求 URL 抛出 I/O 异常。 */
    private static void ensureSuccessfulStatus(HttpResponse<?> response, String url) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("禅道请求失败 (HTTP " + status + "): " + url);
        }
    }

    /** 在内存响应体中识别禅道登录页标记。 */
    private static boolean containsLoginPage(byte[] body) {
        return body != null && new String(body, StandardCharsets.UTF_8).contains(LOGIN_PAGE_INDICATOR);
    }

    /**
     * 流式扫描已下载文件中的登录页标记，避免为检查大型附件而再次整体读入内存。
     */
    private static boolean containsLoginPage(Path file) throws IOException {
        byte[] indicator = LOGIN_PAGE_INDICATOR.getBytes(StandardCharsets.UTF_8);
        int matched = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int value;
            while ((value = input.read()) >= 0) {
                // 维护当前连续匹配长度；失配字符若等于首字符，则立即开始下一次候选匹配。
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

    /** 将表单字段编码为 {@code application/x-www-form-urlencoded} 请求体。 */
    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        return form.entrySet().stream()
            .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
    }

    /** 使用 UTF-8 对单个表单键或值执行 URL 编码，null 按空串处理。 */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 从资源 URL 的路径提取短且安全的扩展名，无法确定时使用 {@code .png}。
     */
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
            // 与 TypeScript 版本保持相同兜底规则：地址无效时按 PNG 文件名保存。
        }
        return ".png";
    }

    /** 将整数值格式化为无小数文本，其他值使用标准双精度文本。 */
    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** 确认页码和每页条数均为正数。 */
    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("页码和每页条数必须大于 0");
        }
    }

    /**
     * 校验详情 ID 非空且不包含路径分隔符或父目录片段，避免 ID 进入 URL/路径时越界。
     */
    private static void requireId(String id, String label) {
        if (isBlank(id)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new IllegalArgumentException(label + "格式无效");
        }
    }

    /** 要求客户端已经成功登录，主要保护会写入当前账号字段的操作。 */
    private void requireLoggedIn() {
        if (isBlank(loggedInAccount)) {
            throw new IllegalStateException("未登录");
        }
    }

    /** 统一判断 null、空串和仅含空白字符的文本。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
