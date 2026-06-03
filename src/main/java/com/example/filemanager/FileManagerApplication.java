package com.example.filemanager;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FileManagerApplication {
    private static final Path BASE_DIR = Path.of("").toAbsolutePath();
    private static final Path DATA_DIR = BASE_DIR.resolve("data");
    private static final Path STORAGE_DIR = BASE_DIR.resolve("storage");
    private static final Path USERS_FILE = DATA_DIR.resolve("users.tsv");
    private static final Path FILES_FILE = DATA_DIR.resolve("files.tsv");
    private static final Path FOLDERS_FILE = DATA_DIR.resolve("folders.tsv");
    private static final Path SHARES_FILE = DATA_DIR.resolve("shares.tsv");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, ClipboardItem> CLIPBOARDS = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, StoredFile> files = new LinkedHashMap<>();
    private final Map<String, Folder> folders = new LinkedHashMap<>();
    private final Map<String, ShareLink> shares = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        FileManagerApplication app = new FileManagerApplication();
        app.init();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", app::route);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("File manager is running at http://localhost:8080");
        System.out.println("Default admin: admin / admin123");
    }

    private void init() throws Exception {
        Files.createDirectories(DATA_DIR);
        Files.createDirectories(STORAGE_DIR);
        loadUsers();
        loadFolders();
        loadFiles();
        loadShares();
        if (users.isEmpty()) {
            User admin = new User(UUID.randomUUID().toString(), "admin", Passwords.hash("admin123"), "ADMIN", true, Instant.now());
            users.put(admin.id, admin);
            saveUsers();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                redirect(ex, currentUser(ex).isPresent() ? "/files" : "/login");
            } else if (path.equals("/login") && method.equals("GET")) {
                loginPage(ex, null);
            } else if (path.equals("/login") && method.equals("POST")) {
                doLogin(ex);
            } else if (path.equals("/register") && method.equals("GET")) {
                registerPage(ex, null);
            } else if (path.equals("/register") && method.equals("POST")) {
                doRegister(ex);
            } else if (path.equals("/logout")) {
                doLogout(ex);
            } else if (path.equals("/files")) {
                requireLogin(ex, this::filesPage);
            } else if (path.equals("/upload") && method.equals("POST")) {
                requireLogin(ex, this::doUpload);
            } else if (path.equals("/files/create") && method.equals("POST")) {
                requireLogin(ex, this::createTextFile);
            } else if (path.equals("/folders/create") && method.equals("POST")) {
                requireLogin(ex, this::createFolder);
            } else if (path.startsWith("/files/move/") && method.equals("POST")) {
                requireLogin(ex, this::moveFile);
            } else if (path.startsWith("/files/rename/") && method.equals("POST")) {
                requireLogin(ex, this::renameFile);
            } else if (path.startsWith("/files/delete/") && method.equals("POST")) {
                requireLogin(ex, this::deleteFile);
            } else if (path.startsWith("/files/copy/") && method.equals("POST")) {
                requireLogin(ex, this::copyFile);
            } else if (path.startsWith("/files/cut/") && method.equals("POST")) {
                requireLogin(ex, this::cutFile);
            } else if (path.equals("/clipboard/paste") && method.equals("POST")) {
                requireLogin(ex, this::pasteClipboard);
            } else if (path.startsWith("/files/tags/") && method.equals("POST")) {
                requireLogin(ex, this::updateFileTags);
            } else if (path.startsWith("/folders/rename/") && method.equals("POST")) {
                requireLogin(ex, this::renameFolder);
            } else if (path.startsWith("/folders/delete/") && method.equals("POST")) {
                requireLogin(ex, this::deleteFolder);
            } else if (path.startsWith("/folders/move/") && method.equals("POST")) {
                requireLogin(ex, this::moveFolder);
            } else if (path.equals("/trash")) {
                requireLogin(ex, this::trashPage);
            } else if (path.startsWith("/trash/restore-file/") && method.equals("POST")) {
                requireLogin(ex, this::restoreFile);
            } else if (path.startsWith("/trash/purge-file/") && method.equals("POST")) {
                requireLogin(ex, this::purgeFile);
            } else if (path.startsWith("/trash/restore-folder/") && method.equals("POST")) {
                requireLogin(ex, this::restoreFolder);
            } else if (path.startsWith("/trash/purge-folder/") && method.equals("POST")) {
                requireLogin(ex, this::purgeFolder);
            } else if (path.startsWith("/folders/download/")) {
                requireLogin(ex, this::downloadFolder);
            } else if (path.startsWith("/download/")) {
                requireLogin(ex, this::downloadFile);
            } else if (path.startsWith("/preview/")) {
                requireLogin(ex, this::previewFile);
            } else if (path.startsWith("/share/create/") && method.equals("POST")) {
                requireLogin(ex, this::createShare);
            } else if (path.equals("/shares")) {
                requireLogin(ex, this::sharesPage);
            } else if (path.startsWith("/share/delete/") && method.equals("POST")) {
                requireLogin(ex, this::deleteShare);
            } else if (path.startsWith("/s/")) {
                publicShare(ex);
            } else if (path.equals("/admin/users")) {
                requireAdmin(ex, this::adminUsersPage);
            } else if (path.startsWith("/admin/users/") && method.equals("POST")) {
                requireAdmin(ex, this::updateUser);
            } else {
                html(ex, 404, layout("未找到", "<h1>404</h1><p>页面不存在。</p>", null));
            }
        } catch (Exception e) {
            e.printStackTrace();
            html(ex, 500, layout("系统错误", "<h1>系统错误</h1><pre>" + esc(e.getMessage()) + "</pre>", currentUser(ex).orElse(null)));
        }
    }

    private void requireLogin(HttpExchange ex, ExchangeHandler handler) throws Exception {
        Optional<User> user = currentUser(ex);
        if (user.isEmpty()) {
            redirect(ex, "/login");
            return;
        }
        handler.handle(ex);
    }

    private void requireAdmin(HttpExchange ex, ExchangeHandler handler) throws Exception {
        Optional<User> user = currentUser(ex);
        if (user.isEmpty()) {
            redirect(ex, "/login");
            return;
        }
        if (!"ADMIN".equals(user.get().role)) {
            html(ex, 403, layout("无权限", "<h1>无权限</h1><p>需要管理员权限。</p>", user.get()));
            return;
        }
        handler.handle(ex);
    }

    private Optional<User> currentUser(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return Optional.empty();
        }
        for (HttpCookie c : HttpCookie.parse(cookie)) {
            if ("FMSESSION".equals(c.getName())) {
                Session session = SESSIONS.get(c.getValue());
                if (session != null && session.expiresAt.isAfter(Instant.now())) {
                    User user = users.get(session.userId);
                    if (user != null && user.enabled) {
                        return Optional.of(user);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void loginPage(HttpExchange ex, String error) throws IOException {
        String body = """
                <section class="auth-card">
                  <div class="auth-brand">
                    <span class="brand-mark">FM</span>
                    <div>
                      <p class="eyebrow">Cloud File Manager</p>
                      <h1>文件管理系统</h1>
                    </div>
                  </div>
                  %s
                  <form class="auth-form" method="post" action="/login">
                    <label>用户名<input name="username" placeholder="请输入用户名" required autofocus></label>
                    <label>密码<input name="password" type="password" placeholder="请输入密码" required></label>
                    <button class="primary-action full" type="submit">登录</button>
                  </form>
                  <p class="switch-link">还没有账号？<a href="/register">注册用户</a></p>
                </section>
                """.formatted(errorBlock(error));
        html(ex, 200, layout("登录", body, null));
    }

    private void doLogin(HttpExchange ex) throws Exception {
        Map<String, String> form = parseForm(ex);
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");
        synchronized (lock) {
            Optional<User> user = users.values().stream().filter(u -> u.username.equals(username)).findFirst();
            if (user.isEmpty() || !user.get().enabled || !Passwords.verify(password, user.get().passwordHash)) {
                loginPage(ex, "用户名或密码错误，或账号已被禁用。");
                return;
            }
            String token = newToken();
            SESSIONS.put(token, new Session(user.get().id, Instant.now().plusSeconds(60 * 60 * 8)));
            ex.getResponseHeaders().add("Set-Cookie", "FMSESSION=" + token + "; Path=/; HttpOnly; SameSite=Lax");
        }
        redirect(ex, "/files");
    }

    private void registerPage(HttpExchange ex, String error) throws IOException {
        String body = """
                <section class="auth-card">
                  <div class="auth-brand">
                    <span class="brand-mark">FM</span>
                    <div>
                      <p class="eyebrow">Create Account</p>
                      <h1>注册账号</h1>
                    </div>
                  </div>
                  %s
                  <form class="auth-form" method="post" action="/register">
                    <label>用户名<input name="username" placeholder="3-32 位中文、英文、数字或下划线" minlength="3" maxlength="32" required autofocus></label>
                    <label>密码<input name="password" type="password" placeholder="至少 6 位" minlength="6" required></label>
                    <button class="primary-action full" type="submit">注册</button>
                  </form>
                  <p class="switch-link">已有账号？<a href="/login">返回登录</a></p>
                </section>
                """.formatted(errorBlock(error));
        html(ex, 200, layout("注册", body, null));
    }

    private void doRegister(HttpExchange ex) throws Exception {
        Map<String, String> form = parseForm(ex);
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");
        if (!username.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]{3,32}")) {
            registerPage(ex, "用户名需为 3-32 位中文、英文、数字或下划线。");
            return;
        }
        if (password.length() < 6) {
            registerPage(ex, "密码至少 6 位。");
            return;
        }
        synchronized (lock) {
            boolean exists = users.values().stream().anyMatch(u -> u.username.equalsIgnoreCase(username));
            if (exists) {
                registerPage(ex, "用户名已存在。");
                return;
            }
            User user = new User(UUID.randomUUID().toString(), username, Passwords.hash(password), "USER", true, Instant.now());
            users.put(user.id, user);
            saveUsers();
        }
        redirect(ex, "/login");
    }

    private void doLogout(HttpExchange ex) throws IOException {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (HttpCookie c : HttpCookie.parse(cookie)) {
                if ("FMSESSION".equals(c.getName())) {
                    SESSIONS.remove(c.getValue());
                }
            }
        }
        ex.getResponseHeaders().add("Set-Cookie", "FMSESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(ex, "/login");
    }

    private void filesPage(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String folderId = queryParam(ex, "folder").orElse("");
        String search = queryParam(ex, "q").orElse("").trim();
        String type = queryParam(ex, "type").orElse("").trim().toLowerCase(Locale.ROOT);
        String tag = queryParam(ex, "tag").orElse("").trim().toLowerCase(Locale.ROOT);
        String content = queryParam(ex, "content").orElse("").trim().toLowerCase(Locale.ROOT);
        String from = queryParam(ex, "from").orElse("").trim();
        String to = queryParam(ex, "to").orElse("").trim();
        if (!folderId.isBlank() && !canAccessActiveFolder(user, folderId)) {
            folderId = "";
        }
        StringBuilder rows = new StringBuilder();
        StringBuilder folderRows = new StringBuilder();
        long totalFiles;
        long totalFolders;
        long totalSize;
        long currentFileCount;
        long currentFolderCount;
        boolean hasFilter = !search.isBlank() || !type.isBlank() || !tag.isBlank() || !content.isBlank()
                || !from.isBlank() || !to.isBlank();
        synchronized (lock) {
            String currentFolderId = folderId;
            String normalizedSearch = search.toLowerCase(Locale.ROOT);
            Predicate<Folder> folderScope = folder -> folder.deletedAt == null && (normalizedSearch.isBlank()
                    ? folder.parentId.equals(currentFolderId)
                    : folder.name.toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    || folderPath(folder.id).toLowerCase(Locale.ROOT).contains(normalizedSearch));
            Predicate<StoredFile> fileScope = f -> f.deletedAt == null
                    && (normalizedSearch.isBlank()
                    ? f.folderId.equals(currentFolderId)
                    : f.originalName.toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    || contentTypeLabel(f).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    || folderPath(f.folderId).toLowerCase(Locale.ROOT).contains(normalizedSearch))
                    && (type.isBlank() || contentTypeLabel(f).toLowerCase(Locale.ROOT).contains(type)
                    || extension(f.originalName).equals(type))
                    && (tag.isBlank() || tagList(f.tags).stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(tag)))
                    && matchesFrom(f.createdAt, from)
                    && matchesTo(f.createdAt, to)
                    && (content.isBlank() || contentMatches(f, content));
            totalFiles = files.values().stream().filter(activeFile(user)).count();
            totalFolders = folders.values().stream().filter(folder -> folder.deletedAt == null && canAccessFolder(user, folder.id)).count();
            totalSize = files.values().stream().filter(activeFile(user)).mapToLong(f -> f.size).sum();
            currentFolderCount = folders.values().stream()
                    .filter(folderScope)
                    .filter(folder -> canAccessFolder(user, folder.id))
                    .count();
            currentFileCount = files.values().stream()
                    .filter(activeFile(user))
                    .filter(fileScope)
                    .count();
            folders.values().stream()
                    .filter(folderScope)
                    .filter(folder -> canAccessFolder(user, folder.id))
                    .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                    .forEach(folder -> folderRows.append(folderRow(folder)));
            files.values().stream()
                    .filter(activeFile(user))
                    .filter(fileScope)
                    .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                    .forEach(f -> rows.append(fileRow(f, user)));
        }
        String parentLink = parentFolderId(folderId)
                .map(parent -> "<a class=\"ghost-action\" href=\"/files" + folderQuery(parent) + "\">返回上级</a>")
                .orElse("");
        String folderPathText = folderBreadcrumbs(folderId);
        String clearSearch = hasFilter ? "<a class=\"ghost-action\" href=\"/files" + folderQuery(folderId) + "\">清除搜索</a>" : "";
        String body = """
                <section class="page-head">
                  <div>
                    <p class="eyebrow">Workspace</p>
                    <h1>%s</h1>
                    <p class="meta path-line">%s</p>
                  </div>
                  <div class="head-actions">%s%s</div>
                </section>
                <form class="search-bar" method="get" action="/files">
                  <input type="hidden" name="folder" value="%s">
                  <input name="q" value="%s" placeholder="搜索文件、文件夹、类型或路径">
                  <input name="type" value="%s" placeholder="类型，如 jpg / 图片">
                  <input name="tag" value="%s" placeholder="标签">
                  <input name="content" value="%s" placeholder="全文检索">
                  <input name="from" value="%s" type="date" title="创建开始日期">
                  <input name="to" value="%s" type="date" title="创建结束日期">
                  <button class="primary-action" type="submit">搜索</button>
                </form>
                <section class="stats-grid">
                  <article class="stat-card"><span>总文件</span><strong>%d</strong><small>%s %d 个</small></article>
                  <article class="stat-card accent-green"><span>文件夹</span><strong>%d</strong><small>%s %d 个</small></article>
                  <article class="stat-card accent-orange"><span>已用空间</span><strong>%s</strong><small>本地存储统计</small></article>
                </section>
                <section class="command-grid">
                  <form class="command-card upload-card" method="post" action="/upload%s" enctype="multipart/form-data">
                    <div>
                      <h2>上传文件</h2>
                      <p>支持一次选择多个文件。断点续传与分片上传已预留为下一阶段传输协议能力。</p>
                    </div>
                    <input type="file" name="file" multiple required>
                    <button class="primary-action" type="submit">上传</button>
                  </form>
                  <form class="command-card" method="post" action="/folders/create">
                    <div>
                      <h2>新建文件夹</h2>
                      <p>用目录整理图片、文档和项目资料。</p>
                    </div>
                    <input type="hidden" name="parentId" value="%s">
                    <input name="name" placeholder="文件夹名称" maxlength="80" required>
                    <button class="secondary-action" type="submit">创建</button>
                  </form>
                  <form class="command-card" method="post" action="/files/create">
                    <div>
                      <h2>新建文本文件</h2>
                      <p>快速创建说明、日志或清单。</p>
                    </div>
                    <input type="hidden" name="folderId" value="%s">
                    <input name="name" placeholder="文件名，例如 notes.txt" maxlength="180" required>
                    <button class="secondary-action" type="submit">新建</button>
                  </form>
                  <form class="command-card" method="post" action="/clipboard/paste">
                    <div>
                      <h2>粘贴到当前目录</h2>
                      <p>配合文件行中的复制/剪切使用。</p>
                    </div>
                    <input type="hidden" name="folderId" value="%s">
                    <button class="primary-action" type="submit">粘贴</button>
                  </form>
                </section>
                <section class="panel folder-panel">
                  <div class="section-title">
                    <h2>%s</h2>
                    <span>%d 个</span>
                  </div>
                  <div class="folder-grid">%s</div>
                </section>
                <section class="panel file-panel">
                  <div class="section-title">
                    <h2>%s</h2>
                    <span>%d 个</span>
                  </div>
                  <div class="table-wrap">
                    <table>
                      <thead><tr><th>文件名</th><th>类型</th><th>大小</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead>
                      <tbody>%s</tbody>
                    </table>
                  </div>
                </section>
                <div id="file-context-menu" class="context-menu" hidden>
                  <form id="context-move-form" method="post" action="">
                    <strong>移动文件</strong>
                    <select name="folderId">%s</select>
                    <button class="primary-action" type="submit">移动到此处</button>
                  </form>
                </div>
                """.formatted(esc(hasFilter ? "搜索结果" : currentFolderName(folderId)), folderPathText, parentLink, clearSearch,
                esc(folderId), esc(search), esc(type), esc(tag), esc(content), esc(from), esc(to),
                totalFiles, hasFilter ? "匹配" : "当前目录", currentFileCount,
                totalFolders, hasFilter ? "匹配" : "当前目录", currentFolderCount, humanSize(totalSize),
                folderQuery(folderId), esc(folderId), esc(folderId), esc(folderId),
                hasFilter ? "匹配的文件夹" : "文件夹",
                currentFolderCount,
                folderRows.isEmpty() ? "<p class=\"empty small\">暂无子文件夹</p>" : folderRows,
                hasFilter ? "匹配的文件" : "文件",
                currentFileCount,
                rows.isEmpty() ? "<tr><td colspan=\"6\" class=\"empty\">暂无文件</td></tr>" : rows,
                folderOptions(user, ""));
        html(ex, 200, layout("文件列表", body, user));
    }

    private Predicate<StoredFile> canAccess(User user) {
        return f -> "ADMIN".equals(user.role) || f.ownerId.equals(user.id);
    }

    private Predicate<StoredFile> activeFile(User user) {
        return f -> canAccess(user).test(f) && f.deletedAt == null;
    }

    private boolean canAccessFolder(User user, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return true;
        }
        Folder folder = folders.get(folderId);
        return folder != null && ("ADMIN".equals(user.role) || folder.ownerId.equals(user.id));
    }

    private boolean canAccessActiveFolder(User user, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return true;
        }
        Folder folder = folders.get(folderId);
        return folder != null && folder.deletedAt == null && canAccessFolder(user, folderId);
    }

    private String folderOptions(User user, String selectedId) {
        StringBuilder options = new StringBuilder();
        options.append("<option value=\"\"").append(selectedId == null || selectedId.isBlank() ? " selected" : "").append(">根目录</option>");
        folders.values().stream()
                .filter(folder -> folder.deletedAt == null && canAccessFolder(user, folder.id))
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .forEach(folder -> options.append("<option value=\"").append(folder.id).append("\"")
                        .append(folder.id.equals(selectedId) ? " selected" : "")
                        .append(">").append(esc(folderPath(folder.id))).append("</option>"));
        return options.toString();
    }

    private String folderMoveOptions(Folder movingFolder) {
        StringBuilder options = new StringBuilder();
        options.append("<option value=\"\"").append(movingFolder.parentId.isBlank() ? " selected" : "").append(">根目录</option>");
        folders.values().stream()
                .filter(folder -> folder.deletedAt == null)
                .filter(folder -> !folder.id.equals(movingFolder.id))
                .filter(folder -> !isFolderDescendant(folder.id, movingFolder.id))
                .sorted((a, b) -> folderPath(a.id).compareToIgnoreCase(folderPath(b.id)))
                .forEach(folder -> options.append("<option value=\"").append(folder.id).append("\"")
                        .append(folder.id.equals(movingFolder.parentId) ? " selected" : "")
                        .append(">").append(esc(folderPath(folder.id))).append("</option>"));
        return options.toString();
    }

    private Optional<String> parentFolderId(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return Optional.empty();
        }
        Folder folder = folders.get(folderId);
        return folder == null || folder.parentId.isBlank() ? Optional.of("") : Optional.of(folder.parentId);
    }

    private String currentFolderName(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return "根目录";
        }
        Folder folder = folders.get(folderId);
        return folder == null ? "根目录" : folder.name;
    }

    private String folderPath(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return "根目录";
        }
        List<String> names = new ArrayList<>();
        String current = folderId;
        int guard = 0;
        while (!current.isBlank() && guard++ < 32) {
            Folder folder = folders.get(current);
            if (folder == null) {
                break;
            }
            names.add(0, folder.name);
            current = folder.parentId;
        }
        return names.isEmpty() ? "根目录" : "根目录 / " + String.join(" / ", names);
    }

    private String folderBreadcrumbs(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return "<a href=\"/files\">根目录</a>";
        }
        List<Folder> path = new ArrayList<>();
        String current = folderId;
        int guard = 0;
        while (!current.isBlank() && guard++ < 32) {
            Folder folder = folders.get(current);
            if (folder == null) {
                break;
            }
            path.add(0, folder);
            current = folder.parentId;
        }
        StringBuilder out = new StringBuilder("<a href=\"/files\">根目录</a>");
        for (Folder folder : path) {
            out.append("<span>/</span><a href=\"/files?folder=")
                    .append(urlEncode(folder.id))
                    .append("\">")
                    .append(esc(folder.name))
                    .append("</a>");
        }
        return out.toString();
    }

    private String folderQuery(String folderId) {
        return folderId == null || folderId.isBlank() ? "" : "?folder=" + urlEncode(folderId);
    }

    private String fileRow(StoredFile f, User viewer) {
        User owner = users.get(f.ownerId);
        String shareButton = """
                <form class="inline" method="post" action="/share/create/%s">
                  <button class="link-action" type="submit">分享</button>
                </form>
                """.formatted(f.id);
        String copyButton = """
                <form class="inline" method="post" action="/files/copy/%s">
                  <button class="link-action" type="submit">复制</button>
                </form>
                """.formatted(f.id);
        String cutButton = """
                <form class="inline" method="post" action="/files/cut/%s">
                  <button class="link-action" type="submit">剪切</button>
                </form>
                """.formatted(f.id);
        String renameForm = """
                <form class="inline rename-form" method="post" action="/files/rename/%s">
                  <input name="name" value="%s" maxlength="180" required>
                  <button class="tiny-action" type="submit">重命名</button>
                </form>
                """.formatted(f.id, esc(f.originalName));
        String tagsForm = """
                <form class="inline rename-form" method="post" action="/files/tags/%s">
                  <input name="tags" value="%s" maxlength="160" placeholder="标签">
                  <button class="tiny-action" type="submit">标签</button>
                </form>
                """.formatted(f.id, esc(f.tags));
        String deleteForm = """
                <form class="inline" method="post" action="/files/delete/%s" onsubmit="return confirm('确定删除这个文件吗？删除后不能从系统内恢复。');">
                  <button class="danger-action" type="submit">删除</button>
                </form>
                """.formatted(f.id);
        return """
                <tr class="file-row" data-file-id="%s">
                  <td>
                    <div class="file-name">
                      <span class="file-badge %s">%s</span>
                      <div><strong>%s</strong><small>%s · %s · %s</small><div class="tag-list">%s</div></div>
                    </div>
                  </td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td class="actions"><a class="link-action" href="/preview/%s">预览</a><a class="link-action" href="/download/%s">下载</a>%s%s%s%s%s%s</td>
                </tr>
                """.formatted(esc(f.id), fileBadgeClass(f), esc(fileShortType(f)), esc(f.originalName), esc(extensionLabel(f.originalName)),
                "修改 " + TIME_FORMAT.format(f.updatedAt), "路径 " + esc(folderPath(f.folderId)), tagBadges(f.tags),
                esc(contentTypeLabel(f)), humanSize(f.size), esc(owner == null ? "未知" : owner.username),
                TIME_FORMAT.format(f.createdAt), f.id, f.id, shareButton, copyButton, cutButton, renameForm, tagsForm, deleteForm);
    }

    private String folderRow(Folder folder) {
        return """
                <article class="folder-card">
                  <a class="folder-open" href="/files?folder=%s">
                    <span class="folder-icon"></span>
                    <span>%s</span>
                  </a>
                  <form class="folder-rename" method="post" action="/folders/rename/%s">
                    <input name="name" value="%s" maxlength="80" required>
                    <button class="tiny-action" type="submit">重命名</button>
                  </form>
                  <form class="folder-rename" method="post" action="/folders/move/%s">
                    <select name="parentId">%s</select>
                    <button class="tiny-action" type="submit">移动</button>
                  </form>
                  <a class="link-action" href="/folders/download/%s">打包下载</a>
                  <form method="post" action="/folders/delete/%s" onsubmit="return confirm('确定删除这个空文件夹吗？');">
                    <button class="danger-action" type="submit">删除</button>
                  </form>
                </article>
                """.formatted(urlEncode(folder.id), esc(folder.name), folder.id, esc(folder.name),
                folder.id, folderMoveOptions(folder), folder.id, folder.id);
    }

    private static String fileShortType(StoredFile f) {
        String ext = extension(f.originalName);
        if (!ext.isBlank()) {
            return ext.length() > 4 ? ext.substring(0, 4).toUpperCase(Locale.ROOT) : ext.toUpperCase(Locale.ROOT);
        }
        if (f.contentType.startsWith("image/")) return "IMG";
        if (f.contentType.startsWith("video/")) return "VID";
        if (f.contentType.startsWith("text/")) return "TXT";
        return "FILE";
    }

    private static String fileBadgeClass(StoredFile f) {
        if (f.contentType.startsWith("image/")) return "tone-image";
        if (f.contentType.startsWith("video/")) return "tone-video";
        if (f.contentType.startsWith("text/") || f.originalName.toLowerCase(Locale.ROOT).endsWith(".md")) return "tone-text";
        if (f.originalName.toLowerCase(Locale.ROOT).endsWith(".docx")) return "tone-doc";
        if (f.originalName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) return "tone-sheet";
        return "tone-file";
    }

    private static String extensionLabel(String name) {
        String ext = extension(name);
        return ext.isBlank() ? "无扩展名" : ext.toUpperCase(Locale.ROOT) + " 文件";
    }

    private static String contentTypeLabel(StoredFile f) {
        if (f.contentType.startsWith("image/")) return "图片";
        if (f.contentType.startsWith("video/")) return "视频";
        if (f.contentType.startsWith("text/")) return "文本";
        String lower = f.originalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) return "Word 文档";
        if (lower.endsWith(".xlsx")) return "Excel 表格";
        return "文件";
    }

    private void createFolder(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        Map<String, String> form = parseForm(ex);
        String parentId = form.getOrDefault("parentId", "");
        if (!parentId.isBlank() && !canAccessFolder(user, parentId)) {
            parentId = "";
        }
        String name = cleanFolderName(form.getOrDefault("name", ""));
        if (name.isBlank()) {
            redirect(ex, "/files" + folderQuery(parentId));
            return;
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        synchronized (lock) {
            folders.put(id, new Folder(id, user.id, parentId, name, now, now, null));
            saveFolders();
        }
        redirect(ex, "/files?folder=" + id);
    }

    private void moveFile(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        String folderId = form.getOrDefault("folderId", "");
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f == null || f.deletedAt != null || !canAccess(user).test(f) || (!folderId.isBlank() && !canAccessActiveFolder(user, folderId))) {
                html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
                return;
            }
            StoredFile moved = new StoredFile(f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                    f.size, f.createdAt, Instant.now(), folderId, f.tags, f.deletedAt);
            files.put(fileId, moved);
            saveFiles();
        }
        redirect(ex, "/files" + folderQuery(folderId));
    }

    private void renameFile(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
                html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
                return;
            }
            String newName = safeFilename(form.getOrDefault("name", f.originalName));
            if (!newName.isBlank()) {
                StoredFile renamed = new StoredFile(f.id, f.ownerId, newName, f.storedName,
                        contentType(newName, f.contentType), f.size, f.createdAt, Instant.now(), f.folderId, f.tags, f.deletedAt);
                files.put(fileId, renamed);
                saveFiles();
            }
            redirect(ex, "/files" + folderQuery(f.folderId));
        }
    }

    private void deleteFile(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        String folderId = "";
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
                html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
                return;
            }
            folderId = f.folderId;
            StoredFile deleted = new StoredFile(f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                    f.size, f.createdAt, Instant.now(), f.folderId, f.tags, Instant.now());
            files.put(fileId, deleted);
            shares.values().removeIf(s -> s.fileId.equals(fileId));
            saveFiles();
            saveShares();
        }
        redirect(ex, "/files" + folderQuery(folderId));
    }

    private void renameFolder(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        String redirectFolder = folderId;
        synchronized (lock) {
            Folder folder = folders.get(folderId);
            if (folder == null || !canAccessFolder(user, folder.id)) {
                html(ex, 404, layout("文件夹不存在", "<h1>文件夹不存在</h1>", user));
                return;
            }
            String name = cleanFolderName(form.getOrDefault("name", folder.name));
            if (!name.isBlank()) {
                folders.put(folderId, new Folder(folder.id, folder.ownerId, folder.parentId, name, folder.createdAt, Instant.now(), folder.deletedAt));
                saveFolders();
            }
        }
        redirect(ex, "/files" + folderQuery(redirectFolder));
    }

    private void deleteFolder(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        String parentId;
        synchronized (lock) {
            Folder folder = folders.get(folderId);
            if (folder == null || !canAccessFolder(user, folder.id)) {
                html(ex, 404, layout("文件夹不存在", "<h1>文件夹不存在</h1>", user));
                return;
            }
            boolean hasChildFolder = folders.values().stream().anyMatch(f -> f.parentId.equals(folderId));
            boolean hasFile = files.values().stream().anyMatch(f -> f.folderId.equals(folderId));
            if (hasChildFolder || hasFile) {
                html(ex, 409, layout("文件夹非空", """
                        <section class="panel">
                          <h1>文件夹非空</h1>
                          <p class="meta">请先移动或删除其中的文件和子文件夹，再删除这个文件夹。</p>
                          <p><a class="ghost-action" href="/files?folder=%s">返回文件夹</a></p>
                        </section>
                        """.formatted(urlEncode(folderId)), user));
                return;
            }
            parentId = folder.parentId;
            folders.put(folderId, new Folder(folder.id, folder.ownerId, folder.parentId, folder.name,
                    folder.createdAt, Instant.now(), Instant.now()));
            saveFolders();
        }
        redirect(ex, "/files" + folderQuery(parentId));
    }

    private void copyFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        StoredFile f = files.get(fileId);
        if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
            html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
            return;
        }
        CLIPBOARDS.put(user.id, new ClipboardItem("file", fileId, "copy"));
        redirect(ex, "/files" + folderQuery(f.folderId));
    }

    private void cutFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        StoredFile f = files.get(fileId);
        if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
            html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
            return;
        }
        CLIPBOARDS.put(user.id, new ClipboardItem("file", fileId, "cut"));
        redirect(ex, "/files" + folderQuery(f.folderId));
    }

    private void pasteClipboard(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        Map<String, String> form = parseForm(ex);
        String folderId = form.getOrDefault("folderId", "");
        if (!folderId.isBlank() && !canAccessActiveFolder(user, folderId)) {
            folderId = "";
        }
        ClipboardItem item = CLIPBOARDS.get(user.id);
        if (item == null) {
            redirect(ex, "/files" + folderQuery(folderId));
            return;
        }
        synchronized (lock) {
            if ("file".equals(item.kind)) {
                StoredFile f = files.get(item.id);
                if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
                    CLIPBOARDS.remove(user.id);
                    redirect(ex, "/files" + folderQuery(folderId));
                    return;
                }
                if ("cut".equals(item.mode)) {
                    files.put(f.id, new StoredFile(f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                            f.size, f.createdAt, Instant.now(), folderId, f.tags, null));
                    CLIPBOARDS.remove(user.id);
                } else {
                    String id = UUID.randomUUID().toString();
                    String ext = extension(f.originalName);
                    String storedName = id + (ext.isEmpty() ? "" : "." + ext);
                    Files.copy(STORAGE_DIR.resolve(f.storedName), STORAGE_DIR.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
                    Instant now = Instant.now();
                    files.put(id, new StoredFile(id, user.id, copyName(f.originalName), storedName, f.contentType,
                            f.size, now, now, folderId, f.tags, null));
                }
                saveFiles();
            }
        }
        redirect(ex, "/files" + folderQuery(folderId));
    }

    private void updateFileTags(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
                html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
                return;
            }
            files.put(fileId, new StoredFile(f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                    f.size, f.createdAt, Instant.now(), f.folderId, cleanTags(form.getOrDefault("tags", "")), null));
            saveFiles();
            redirect(ex, "/files" + folderQuery(f.folderId));
        }
    }

    private void moveFolder(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        String targetId = form.getOrDefault("parentId", "");
        synchronized (lock) {
            Folder folder = folders.get(folderId);
            if (folder == null || folder.deletedAt != null || !canAccessFolder(user, folderId)
                    || (!targetId.isBlank() && !canAccessActiveFolder(user, targetId))
                    || folderId.equals(targetId) || isFolderDescendant(targetId, folderId)) {
                html(ex, 400, layout("无法移动文件夹", "<h1>无法移动文件夹</h1><p class=\"meta\">目标位置无效，或会造成循环嵌套。</p>", user));
                return;
            }
            folders.put(folderId, new Folder(folder.id, folder.ownerId, targetId, folder.name,
                    folder.createdAt, Instant.now(), null));
            saveFolders();
        }
        redirect(ex, "/files" + folderQuery(targetId));
    }

    private void trashPage(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        StringBuilder folderRows = new StringBuilder();
        StringBuilder fileRows = new StringBuilder();
        synchronized (lock) {
            folders.values().stream()
                    .filter(f -> f.deletedAt != null && canAccessFolder(user, f.id))
                    .sorted((a, b) -> b.deletedAt.compareTo(a.deletedAt))
                    .forEach(f -> folderRows.append("""
                            <tr>
                              <td><strong>%s</strong><small>%s</small></td>
                              <td>%s</td>
                              <td class="actions">
                                <form class="inline" method="post" action="/trash/restore-folder/%s"><button class="tiny-action" type="submit">还原</button></form>
                                <form class="inline" method="post" action="/trash/purge-folder/%s" onsubmit="return confirm('确定彻底删除这个文件夹及其内容吗？');"><button class="danger-action" type="submit">彻底删除</button></form>
                              </td>
                            </tr>
                            """.formatted(esc(f.name), esc(folderPath(f.parentId)), TIME_FORMAT.format(f.deletedAt), f.id, f.id)));
            files.values().stream()
                    .filter(f -> f.deletedAt != null && canAccess(user).test(f))
                    .sorted((a, b) -> b.deletedAt.compareTo(a.deletedAt))
                    .forEach(f -> fileRows.append("""
                            <tr>
                              <td><div class="file-name"><span class="file-badge %s">%s</span><div><strong>%s</strong><small>%s</small></div></div></td>
                              <td>%s</td>
                              <td>%s</td>
                              <td class="actions">
                                <form class="inline" method="post" action="/trash/restore-file/%s"><button class="tiny-action" type="submit">还原</button></form>
                                <form class="inline" method="post" action="/trash/purge-file/%s" onsubmit="return confirm('确定彻底删除这个文件吗？');"><button class="danger-action" type="submit">彻底删除</button></form>
                              </td>
                            </tr>
                            """.formatted(fileBadgeClass(f), esc(fileShortType(f)), esc(f.originalName),
                            esc(folderPath(f.folderId)), humanSize(f.size), TIME_FORMAT.format(f.deletedAt), f.id, f.id)));
        }
        String body = """
                <section class="page-head">
                  <div>
                    <p class="eyebrow">Recycle Bin</p>
                    <h1>回收站</h1>
                    <p class="meta path-line">删除的文件会先暂存在这里，可还原或彻底删除。</p>
                  </div>
                  <div class="head-actions"><a class="ghost-action" href="/files">返回文件中心</a></div>
                </section>
                <section class="panel">
                  <div class="section-title"><h2>已删除文件夹</h2><span>%d 个</span></div>
                  <div class="table-wrap"><table><thead><tr><th>文件夹</th><th>删除时间</th><th>操作</th></tr></thead><tbody>%s</tbody></table></div>
                </section>
                <section class="panel">
                  <div class="section-title"><h2>已删除文件</h2><span>%d 个</span></div>
                  <div class="table-wrap"><table><thead><tr><th>文件</th><th>大小</th><th>删除时间</th><th>操作</th></tr></thead><tbody>%s</tbody></table></div>
                </section>
                """.formatted(deletedFolderCount(user),
                folderRows.isEmpty() ? "<tr><td colspan=\"3\" class=\"empty\">暂无已删除文件夹</td></tr>" : folderRows,
                deletedFileCount(user),
                fileRows.isEmpty() ? "<tr><td colspan=\"4\" class=\"empty\">暂无已删除文件</td></tr>" : fileRows);
        html(ex, 200, layout("回收站", body, user));
    }

    private void restoreFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f != null && canAccess(user).test(f)) {
                String folderId = canAccessActiveFolder(user, f.folderId) ? f.folderId : "";
                files.put(fileId, new StoredFile(f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                        f.size, f.createdAt, Instant.now(), folderId, f.tags, null));
                saveFiles();
            }
        }
        redirect(ex, "/trash");
    }

    private void purgeFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String fileId = lastPathPart(ex);
        synchronized (lock) {
            StoredFile f = files.get(fileId);
            if (f != null && f.deletedAt != null && canAccess(user).test(f)) {
                files.remove(fileId);
                shares.values().removeIf(s -> s.fileId.equals(fileId));
                saveFiles();
                saveShares();
                Files.deleteIfExists(STORAGE_DIR.resolve(f.storedName));
            }
        }
        redirect(ex, "/trash");
    }

    private void restoreFolder(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        synchronized (lock) {
            Folder folder = folders.get(folderId);
            if (folder != null && canAccessFolder(user, folderId)) {
                String parentId = canAccessActiveFolder(user, folder.parentId) ? folder.parentId : "";
                folders.put(folderId, new Folder(folder.id, folder.ownerId, parentId, folder.name,
                        folder.createdAt, Instant.now(), null));
                saveFolders();
            }
        }
        redirect(ex, "/trash");
    }

    private void purgeFolder(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        synchronized (lock) {
            Folder folder = folders.get(folderId);
            if (folder != null && folder.deletedAt != null && canAccessFolder(user, folderId)) {
                purgeFolderTree(folderId);
                saveFolders();
                saveFiles();
                saveShares();
            }
        }
        redirect(ex, "/trash");
    }

    private void doUpload(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        String folderId = queryParam(ex, "folder").orElse("");
        if (!folderId.isBlank() && !canAccessActiveFolder(user, folderId)) {
            folderId = "";
        }
        List<MultipartFile> parts = parseMultipartFiles(ex);
        if (parts.isEmpty()) {
            html(ex, 400, layout("上传失败", "<h1>上传失败</h1><p>请选择文件。</p>", user));
            return;
        }
        synchronized (lock) {
            for (MultipartFile part : parts) {
                if (part.bytes.length == 0) {
                    continue;
                }
                String id = UUID.randomUUID().toString();
                String safeName = safeFilename(part.filename);
                String ext = extension(safeName);
                Path target = STORAGE_DIR.resolve(id + (ext.isEmpty() ? "" : "." + ext));
                Files.write(target, part.bytes);
                Instant now = Instant.now();
                StoredFile stored = new StoredFile(id, user.id, safeName, target.getFileName().toString(),
                        contentType(safeName, part.contentType), part.bytes.length, now, now, folderId, "", null);
                files.put(id, stored);
            }
            saveFiles();
        }
        redirect(ex, "/files" + folderQuery(folderId));
    }

    private void createTextFile(HttpExchange ex) throws Exception {
        User user = currentUser(ex).orElseThrow();
        Map<String, String> form = parseForm(ex);
        String folderId = form.getOrDefault("folderId", "");
        if (!folderId.isBlank() && !canAccessActiveFolder(user, folderId)) {
            folderId = "";
        }
        String name = safeFilename(form.getOrDefault("name", "untitled.txt"));
        if (extension(name).isBlank()) {
            name = name + ".txt";
        }
        String id = UUID.randomUUID().toString();
        String ext = extension(name);
        Path target = STORAGE_DIR.resolve(id + (ext.isEmpty() ? "" : "." + ext));
        Files.writeString(target, "", StandardCharsets.UTF_8);
        Instant now = Instant.now();
        synchronized (lock) {
            files.put(id, new StoredFile(id, user.id, name, target.getFileName().toString(),
                    contentType(name, "text/plain; charset=utf-8"), 0, now, now, folderId, "", null));
            saveFiles();
        }
        redirect(ex, "/files" + folderQuery(folderId));
    }

    private void downloadFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String id = lastPathPart(ex);
        StoredFile f = files.get(id);
        if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
            html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
            return;
        }
        sendFile(ex, f, true);
    }

    private void downloadFolder(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String folderId = lastPathPart(ex);
        Folder folder = folders.get(folderId);
        if (folder == null || folder.deletedAt != null || !canAccessFolder(user, folderId)) {
            html(ex, 404, layout("文件夹不存在", "<h1>文件夹不存在</h1>", user));
            return;
        }
        byte[] zipBytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            addFolderToZip(user, folderId, cleanZipName(folder.name) + "/", zip);
            zip.finish();
            zipBytes = out.toByteArray();
        }
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=\"" + safeHeader(folder.name) + ".zip\"");
        ex.sendResponseHeaders(200, zipBytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(zipBytes);
        }
    }

    private void previewFile(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String id = lastPathPart(ex);
        StoredFile f = files.get(id);
        if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
            html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
            return;
        }
        html(ex, 200, layout("预览 - " + f.originalName, previewBody(f, "/download/" + f.id), user));
    }

    private void createShare(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String id = lastPathPart(ex);
        StoredFile f = files.get(id);
        if (f == null || f.deletedAt != null || !canAccess(user).test(f)) {
            html(ex, 404, layout("文件不存在", "<h1>文件不存在</h1>", user));
            return;
        }
        String token = newToken();
        synchronized (lock) {
            shares.put(token, new ShareLink(token, f.id, user.id, Instant.now()));
            saveShares();
        }
        String url = "/s/" + token;
        String body = """
                <section class="panel">
                  <div class="section-title">
                    <h1>分享链接已生成</h1>
                    <a class="ghost-action" href="/files">返回文件列表</a>
                  </div>
                  <p class="share-url"><a href="%s">%s</a></p>
                </section>
                """.formatted(url, url);
        html(ex, 200, layout("分享文件", body, user));
    }

    private void sharesPage(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        StringBuilder rows = new StringBuilder();
        synchronized (lock) {
            shares.values().stream()
                    .filter(s -> "ADMIN".equals(user.role) || s.ownerId.equals(user.id))
                    .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                    .forEach(s -> {
                        StoredFile f = files.get(s.fileId);
                        if (f == null || f.deletedAt != null) {
                            return;
                        }
                        rows.append("""
                                <tr>
                                  <td>
                                    <div class="file-name">
                                      <span class="file-badge %s">%s</span>
                                      <div><strong>%s</strong><small>%s</small></div>
                                    </div>
                                  </td>
                                  <td><a class="share-path" href="/s/%s">/s/%s</a></td>
                                  <td>%s</td>
                                  <td class="actions">
                                    <a class="link-action" href="/s/%s">打开</a>
                                    <form class="inline" method="post" action="/share/delete/%s" onsubmit="return confirm('确定取消这个分享链接吗？');">
                                      <button class="danger-action" type="submit">取消分享</button>
                                    </form>
                                  </td>
                                </tr>
                                """.formatted(fileBadgeClass(f), esc(fileShortType(f)), esc(f.originalName),
                                esc(folderPath(f.folderId)), esc(s.token), esc(s.token),
                                TIME_FORMAT.format(s.createdAt), esc(s.token), esc(s.token)));
                    });
        }
        String body = """
                <section class="page-head">
                  <div>
                    <p class="eyebrow">Sharing Center</p>
                    <h1>分享管理</h1>
                    <p class="meta path-line">集中查看和撤销已经生成的公开分享链接。</p>
                  </div>
                  <div class="head-actions"><a class="ghost-action" href="/files">返回文件中心</a></div>
                </section>
                <section class="panel">
                  <div class="section-title">
                    <h2>分享链接</h2>
                    <span>%d 个</span>
                  </div>
                  <div class="table-wrap">
                    <table>
                      <thead><tr><th>文件</th><th>链接</th><th>创建时间</th><th>操作</th></tr></thead>
                      <tbody>%s</tbody>
                    </table>
                  </div>
                </section>
                """.formatted(shareCount(user), rows.isEmpty() ? "<tr><td colspan=\"4\" class=\"empty\">暂无分享链接</td></tr>" : rows);
        html(ex, 200, layout("分享管理", body, user));
    }

    private long shareCount(User user) {
        return shares.values().stream()
                .filter(s -> "ADMIN".equals(user.role) || s.ownerId.equals(user.id))
                .filter(s -> files.containsKey(s.fileId))
                .count();
    }

    private void deleteShare(HttpExchange ex) throws IOException {
        User user = currentUser(ex).orElseThrow();
        String token = lastPathPart(ex);
        synchronized (lock) {
            ShareLink share = shares.get(token);
            if (share == null || (!"ADMIN".equals(user.role) && !share.ownerId.equals(user.id))) {
                html(ex, 404, layout("分享不存在", "<h1>分享不存在</h1>", user));
                return;
            }
            shares.remove(token);
            saveShares();
        }
        redirect(ex, "/shares");
    }

    private long deletedFileCount(User user) {
        return files.values().stream()
                .filter(f -> f.deletedAt != null && canAccess(user).test(f))
                .count();
    }

    private long deletedFolderCount(User user) {
        return folders.values().stream()
                .filter(f -> f.deletedAt != null && canAccessFolder(user, f.id))
                .count();
    }

    private boolean isFolderDescendant(String possibleChildId, String possibleParentId) {
        String current = possibleChildId;
        int guard = 0;
        while (current != null && !current.isBlank() && guard++ < 64) {
            if (current.equals(possibleParentId)) {
                return true;
            }
            Folder folder = folders.get(current);
            current = folder == null ? "" : folder.parentId;
        }
        return false;
    }

    private void purgeFolderTree(String folderId) throws IOException {
        List<String> childFolders = folders.values().stream()
                .filter(f -> f.parentId.equals(folderId))
                .map(f -> f.id)
                .toList();
        for (String child : childFolders) {
            purgeFolderTree(child);
        }
        List<StoredFile> childFiles = files.values().stream()
                .filter(f -> f.folderId.equals(folderId))
                .toList();
        for (StoredFile f : childFiles) {
            files.remove(f.id);
            shares.values().removeIf(s -> s.fileId.equals(f.id));
            Files.deleteIfExists(STORAGE_DIR.resolve(f.storedName));
        }
        folders.remove(folderId);
    }

    private static String copyName(String originalName) {
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            return originalName.substring(0, dot) + " - 副本" + originalName.substring(dot);
        }
        return originalName + " - 副本";
    }

    private void publicShare(HttpExchange ex) throws IOException {
        String token = lastPathPart(ex);
        ShareLink share = shares.get(token);
        StoredFile f = share == null ? null : files.get(share.fileId);
        if (f == null || f.deletedAt != null) {
            html(ex, 404, layout("分享不存在", "<h1>分享不存在</h1><p>链接无效或文件已删除。</p>", null));
            return;
        }
        if (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("download=1")) {
            sendFile(ex, f, true);
            return;
        }
        String body = previewBody(f, "/s/" + token + "?download=1");
        html(ex, 200, layout("分享 - " + f.originalName, body, null));
    }

    private String previewBody(StoredFile f, String downloadUrl) {
        Path path = STORAGE_DIR.resolve(f.storedName);
        String lower = f.originalName.toLowerCase(Locale.ROOT);
        String content;
        try {
            if (f.contentType.startsWith("image/")) {
                content = "<img class=\"preview-media\" src=\"" + downloadUrl + "\" alt=\"preview\">";
            } else if (f.contentType.startsWith("video/")) {
                content = "<video class=\"preview-media\" src=\"" + downloadUrl + "\" controls></video>";
            } else if (f.contentType.startsWith("text/") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".log")) {
                content = "<pre class=\"text-preview\">" + esc(readText(path)) + "</pre>";
            } else if (lower.endsWith(".docx")) {
                content = "<article class=\"doc-preview\">" + docxPreview(path) + "</article>";
            } else if (lower.endsWith(".xlsx")) {
                content = xlsxPreview(path);
            } else {
                content = "<p>该格式暂不支持浏览器内直接预览，可下载后查看。</p>";
            }
        } catch (Exception e) {
            content = "<p>预览失败：" + esc(e.getMessage()) + "</p>";
        }
        return """
                <section class="panel">
                  <div class="section-title">
                    <div>
                      <h1>%s</h1>
                      <p class="meta">%s · %s</p>
                    </div>
                    <a class="primary-action" href="%s">下载文件</a>
                  </div>
                  <div class="preview">%s</div>
                </section>
                """.formatted(esc(f.originalName), esc(f.contentType), humanSize(f.size), downloadUrl, content);
    }

    private void adminUsersPage(HttpExchange ex) throws IOException {
        User viewer = currentUser(ex).orElseThrow();
        StringBuilder rows = new StringBuilder();
        synchronized (lock) {
            users.values().stream()
                    .sorted((a, b) -> a.createdAt.compareTo(b.createdAt))
                    .forEach(u -> rows.append("""
                            <tr>
                              <td>%s</td>
                              <td>%s</td>
                              <td>%s</td>
                              <td>%s</td>
                              <td>
                                <form method="post" action="/admin/users/%s">
                                  <select name="role">
                                    <option value="USER" %s>USER</option>
                                    <option value="ADMIN" %s>ADMIN</option>
                                  </select>
                                  <label class="check"><input type="checkbox" name="enabled" %s>启用</label>
                                  <button type="submit">保存</button>
                                </form>
                              </td>
                            </tr>
                            """.formatted(esc(u.username), esc(u.role), u.enabled ? "启用" : "禁用",
                            TIME_FORMAT.format(u.createdAt), u.id,
                            "USER".equals(u.role) ? "selected" : "",
                            "ADMIN".equals(u.role) ? "selected" : "",
                            u.enabled ? "checked" : "")));
        }
        String body = """
                <section class="panel">
                  <div class="section-title">
                    <h1>用户管理</h1>
                    <span>%d 位用户</span>
                  </div>
                  <div class="table-wrap">
                    <table>
                      <thead><tr><th>用户名</th><th>角色</th><th>状态</th><th>注册时间</th><th>管理</th></tr></thead>
                      <tbody>%s</tbody>
                    </table>
                  </div>
                </section>
                """.formatted(users.size(), rows);
        html(ex, 200, layout("用户管理", body, viewer));
    }

    private void updateUser(HttpExchange ex) throws Exception {
        User viewer = currentUser(ex).orElseThrow();
        String userId = lastPathPart(ex);
        Map<String, String> form = parseForm(ex);
        synchronized (lock) {
            User target = users.get(userId);
            if (target != null) {
                String role = form.getOrDefault("role", target.role);
                target.role = "ADMIN".equals(role) ? "ADMIN" : "USER";
                target.enabled = form.containsKey("enabled") || target.id.equals(viewer.id);
                saveUsers();
            }
        }
        redirect(ex, "/admin/users");
    }

    private void sendFile(HttpExchange ex, StoredFile f, boolean attachment) throws IOException {
        Path path = STORAGE_DIR.resolve(f.storedName);
        if (!Files.exists(path)) {
            html(ex, 404, "文件丢失");
            return;
        }
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", f.contentType);
        String disposition = attachment ? "attachment" : "inline";
        headers.set("Content-Disposition", disposition + "; filename=\"" + safeHeader(f.originalName) + "\"");
        ex.sendResponseHeaders(200, Files.size(path));
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(path, os);
        }
    }

    private void addFolderToZip(User user, String folderId, String prefix, ZipOutputStream zip) throws IOException {
        List<StoredFile> childFiles = files.values().stream()
                .filter(f -> f.deletedAt == null && f.folderId.equals(folderId) && canAccess(user).test(f))
                .toList();
        for (StoredFile f : childFiles) {
            Path path = STORAGE_DIR.resolve(f.storedName);
            if (!Files.exists(path)) {
                continue;
            }
            ZipEntry entry = new ZipEntry(prefix + cleanZipName(f.originalName));
            zip.putNextEntry(entry);
            Files.copy(path, zip);
            zip.closeEntry();
        }
        List<Folder> childFolders = folders.values().stream()
                .filter(f -> f.deletedAt == null && f.parentId.equals(folderId) && canAccessFolder(user, f.id))
                .toList();
        for (Folder folder : childFolders) {
            addFolderToZip(user, folder.id, prefix + cleanZipName(folder.name) + "/", zip);
        }
    }

    private static String cleanZipName(String name) {
        String cleaned = name == null || name.isBlank() ? "item" : name.replace("\\", "_").replace("/", "_");
        return cleaned.replace("\r", "_").replace("\n", "_");
    }

    private MultipartFile parseMultipart(HttpExchange ex) throws IOException {
        List<MultipartFile> files = parseMultipartFiles(ex);
        return files.isEmpty() ? null : files.get(0);
    }

    private List<MultipartFile> parseMultipartFiles(HttpExchange ex) throws IOException {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("boundary=([^;]+)").matcher(contentType);
        if (!matcher.find()) {
            return List.of();
        }
        String boundary = "--" + matcher.group(1).replace("\"", "");
        byte[] body = readAll(ex.getRequestBody());
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        List<MultipartFile> parts = new ArrayList<>();
        int partStart = raw.indexOf(boundary);
        while (partStart >= 0) {
            int headersStart = partStart + boundary.length() + 2;
            int headersEnd = raw.indexOf("\r\n\r\n", headersStart);
            if (headersEnd < 0) {
                break;
            }
            String headers = raw.substring(headersStart, headersEnd);
            int dataStart = headersEnd + 4;
            int dataEnd = raw.indexOf("\r\n" + boundary, dataStart);
            if (dataEnd < 0) {
                break;
            }
            if (headers.contains("name=\"file\"")) {
                String filename = extractMultipartFilename(headers);
                String partType = match(headers, "Content-Type:\\s*([^\\r\\n]+)").orElse("application/octet-stream");
                byte[] bytes = new byte[dataEnd - dataStart];
                System.arraycopy(body, dataStart, bytes, 0, bytes.length);
                parts.add(new MultipartFile(filename, partType.trim(), bytes));
            }
            partStart = raw.indexOf(boundary, dataEnd + boundary.length());
        }
        return parts;
    }

    private static String extractMultipartFilename(String headers) {
        Optional<String> encoded = match(headers, "filename\\*=UTF-8''([^;\\r\\n]+)");
        if (encoded.isPresent()) {
            return urlDecode(encoded.get().replace("\"", ""));
        }
        String raw = match(headers, "filename=\"([^\"]*)\"").orElse("upload.bin");
        return new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> form = new HashMap<>();
        if (body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            form.put(urlDecode(key), urlDecode(value));
        }
        return form;
    }

    private String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Charset charset = looksUtf8(bytes) ? StandardCharsets.UTF_8 : Charset.defaultCharset();
        String text = new String(bytes, charset);
        return text.length() > 200_000 ? text.substring(0, 200_000) + "\n\n[内容过长，已截断]" : text;
    }

    private String docxPreview(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                return "<p>未找到 Word 正文。</p>";
            }
            String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            String text = xml.replaceAll("<w:tab\\s*/>", "\t")
                    .replaceAll("</w:p>", "\n")
                    .replaceAll("<[^>]+>", "");
            text = xmlUnescape(text).trim();
            if (text.isBlank()) {
                return "<p>文档没有可提取的文本。</p>";
            }
            StringBuilder html = new StringBuilder();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    html.append("<p>").append(esc(line)).append("</p>");
                }
            }
            return html.toString();
        }
    }

    private String xlsxPreview(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> shared = readSharedStrings(zip);
            List<? extends ZipEntry> sheets = zip.stream()
                    .filter(e -> e.getName().matches("xl/worksheets/sheet\\d+\\.xml"))
                    .sorted((a, b) -> a.getName().compareTo(b.getName()))
                    .toList();
            if (sheets.isEmpty()) {
                return "<p>未找到工作表。</p>";
            }
            StringBuilder out = new StringBuilder();
            int sheetNo = 1;
            for (ZipEntry sheet : sheets.subList(0, Math.min(sheets.size(), 3))) {
                String xml = new String(zip.getInputStream(sheet).readAllBytes(), StandardCharsets.UTF_8);
                out.append("<h2>Sheet ").append(sheetNo++).append("</h2><table class=\"sheet\">");
                Matcher rowMatcher = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(xml);
                int rowCount = 0;
                while (rowMatcher.find() && rowCount++ < 100) {
                    out.append("<tr>");
                    Matcher cellMatcher = Pattern.compile("<c([^>]*)>(.*?)</c>", Pattern.DOTALL).matcher(rowMatcher.group(1));
                    int cellCount = 0;
                    while (cellMatcher.find() && cellCount++ < 30) {
                        String attrs = cellMatcher.group(1);
                        String cell = cellMatcher.group(2);
                        String value = match(cell, "<v>(.*?)</v>").orElse("");
                        if (attrs.contains(" t=\"s\"") && !value.isBlank()) {
                            int idx = Integer.parseInt(value);
                            value = idx >= 0 && idx < shared.size() ? shared.get(idx) : value;
                        }
                        out.append("<td>").append(esc(xmlUnescape(value))).append("</td>");
                    }
                    out.append("</tr>");
                }
                out.append("</table>");
            }
            return out.toString();
        }
    }

    private List<String> readSharedStrings(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        List<String> values = new ArrayList<>();
        if (entry == null) {
            return values;
        }
        String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<si.*?</si>", Pattern.DOTALL).matcher(xml);
        while (matcher.find()) {
            String item = matcher.group().replaceAll("<[^>]+>", "");
            values.add(xmlUnescape(item));
        }
        return values;
    }

    private String layout(String title, String body, User user) {
        String style = """
                  <style>
                    :root{font-family:Inter,Arial,"Microsoft YaHei",sans-serif;color:#dbeafe;background:#06111f}
                    *{box-sizing:border-box}
                    body{margin:0;background:
                      radial-gradient(circle at 20% 0%,rgba(34,211,238,.16),transparent 32%),
                      radial-gradient(circle at 85% 12%,rgba(59,130,246,.22),transparent 30%),
                      linear-gradient(135deg,#06111f 0%,#0b1e3a 48%,#071525 100%);color:#dbeafe}
                    body:before{content:"";position:fixed;inset:0;pointer-events:none;background-image:linear-gradient(rgba(125,211,252,.08) 1px,transparent 1px),linear-gradient(90deg,rgba(125,211,252,.08) 1px,transparent 1px);background-size:42px 42px;mask-image:linear-gradient(to bottom,rgba(0,0,0,.7),transparent 78%)}
                    a{color:#67e8f9;text-decoration:none}
                    button,input,select{font:inherit}
                    button{cursor:pointer}
                    .auth-page{min-height:100vh;background:
                      radial-gradient(circle at 30% 10%,rgba(34,211,238,.22),transparent 34%),
                      radial-gradient(circle at 75% 80%,rgba(37,99,235,.28),transparent 36%),
                      linear-gradient(145deg,#06111f,#0b1e3a 55%,#020617)}
                    .auth-shell{min-height:100vh;display:grid;place-items:center;padding:24px}
                    .auth-card{width:min(430px,100%);background:rgba(8,24,46,.78);border:1px solid rgba(103,232,249,.32);border-radius:8px;padding:28px;box-shadow:0 24px 80px rgba(0,0,0,.42),0 0 44px rgba(34,211,238,.12);backdrop-filter:blur(16px)}
                    .auth-brand{display:flex;align-items:center;gap:14px;margin-bottom:22px}
                    .brand-mark{display:grid;place-items:center;width:46px;height:46px;border-radius:8px;background:linear-gradient(135deg,#0891b2,#2563eb);color:white;font-weight:800;letter-spacing:.04em;box-shadow:0 0 26px rgba(34,211,238,.36)}
                    .eyebrow{margin:0 0 6px;color:#67e8f9;font-size:12px;font-weight:800;text-transform:uppercase}
                    h1{margin:0;color:#f8fbff;font-size:26px;line-height:1.2}
                    h2{margin:0;color:#f8fbff;font-size:17px;line-height:1.25}
                    .auth-form{display:grid;gap:14px}
                    label{display:grid;gap:7px;color:#bfdbfe;font-weight:700;font-size:14px}
                    input,select{width:100%;min-height:40px;border:1px solid rgba(125,211,252,.28);border-radius:8px;background:rgba(2,8,23,.72);padding:9px 11px;color:#e0f2fe;outline:none}
                    input::placeholder{color:#6b94bd}
                    select option{background:#0b1e3a;color:#dbeafe}
                    input:focus,select:focus{border-color:#22d3ee;box-shadow:0 0 0 3px rgba(34,211,238,.15),0 0 18px rgba(34,211,238,.18)}
                    .primary-action,.secondary-action,.ghost-action,.tiny-action,.link-action,.danger-action{border:0;border-radius:8px;display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:38px;padding:9px 13px;font-weight:800;text-decoration:none;white-space:nowrap}
                    .primary-action{background:linear-gradient(135deg,#0ea5e9,#2563eb);color:white;box-shadow:0 0 24px rgba(14,165,233,.28)}
                    .secondary-action{background:linear-gradient(135deg,#06b6d4,#0891b2);color:white;box-shadow:0 0 22px rgba(6,182,212,.24)}
                    .ghost-action{background:rgba(14,165,233,.12);color:#93f4ff;border:1px solid rgba(125,211,252,.32)}
                    .tiny-action{min-height:34px;padding:7px 10px;background:rgba(37,99,235,.16);color:#bae6fd;border:1px solid rgba(125,211,252,.28)}
                    .danger-action{min-height:34px;padding:7px 10px;background:rgba(244,63,94,.14);color:#fecdd3;border:1px solid rgba(251,113,133,.35)}
                    .link-action{min-height:30px;padding:4px 2px;background:transparent;color:#67e8f9;border:0;box-shadow:none}
                    .full{width:100%}
                    .switch-link{margin:18px 0 0;color:#93aecd;text-align:center}
                    .error{margin:0 0 16px;background:rgba(244,63,94,.14);border:1px solid rgba(251,113,133,.38);color:#fecdd3;padding:10px 12px;border-radius:8px}
                    .app-shell{min-height:100vh;display:grid;grid-template-columns:240px minmax(0,1fr)}
                    .sidebar{background:rgba(4,15,31,.86);border-right:1px solid rgba(125,211,252,.18);padding:22px 16px;display:flex;flex-direction:column;gap:22px;box-shadow:12px 0 40px rgba(0,0,0,.18);backdrop-filter:blur(16px)}
                    .side-brand{display:flex;align-items:center;gap:12px;color:#f8fbff}
                    .side-brand strong{display:block;font-size:17px}
                    .side-brand span:last-child{display:block;color:#7dd3fc;font-size:12px;margin-top:2px}
                    .side-nav{display:grid;gap:8px}
                    .side-nav a{display:flex;align-items:center;min-height:42px;padding:10px 12px;border-radius:8px;color:#bfdbfe;font-weight:800;border:1px solid transparent}
                    .side-nav a:hover,.side-nav a.active{background:rgba(14,165,233,.14);color:#e0f2fe;border-color:rgba(34,211,238,.28);box-shadow:inset 0 0 20px rgba(34,211,238,.08)}
                    .external-search{display:grid;gap:10px;background:rgba(8,24,46,.72);border:1px solid rgba(125,211,252,.22);border-radius:8px;padding:12px}
                    .external-search label{display:block;color:#67e8f9;font-size:12px;font-weight:900;text-transform:uppercase}
                    .external-search-row{display:grid;grid-template-columns:minmax(0,1fr) 42px;gap:8px}
                    .external-search input{min-width:0;min-height:38px;padding:8px 10px}
                    .external-search-button{width:42px;min-height:38px;border:0;border-radius:8px;background:linear-gradient(135deg,#06b6d4,#2563eb);color:white;font-weight:900;box-shadow:0 0 20px rgba(14,165,233,.24)}
                    .storage-note{margin-top:auto;background:rgba(8,24,46,.72);border:1px solid rgba(125,211,252,.22);border-radius:8px;padding:12px;color:#93aecd}
                    .storage-note strong{display:block;color:#e0f2fe;margin-top:4px}
                    .content-shell{min-width:0}
                    .topbar{height:68px;background:rgba(4,15,31,.72);border-bottom:1px solid rgba(125,211,252,.18);display:flex;align-items:center;justify-content:space-between;padding:0 28px;position:sticky;top:0;z-index:5;backdrop-filter:blur(16px)}
                    .topbar-title{font-weight:900;color:#f8fbff}
                    .user-tools{display:flex;align-items:center;gap:10px}
                    .user-pill{display:flex;align-items:center;gap:8px;background:rgba(8,24,46,.72);border:1px solid rgba(125,211,252,.25);border-radius:999px;padding:7px 11px;color:#dbeafe;font-weight:800}
                    .role-dot{width:8px;height:8px;border-radius:999px;background:#22d3ee;box-shadow:0 0 12px rgba(34,211,238,.85)}
                    main{max-width:1180px;margin:0 auto;padding:28px}
                    .page-head{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;margin-bottom:18px}
                    .path-line{margin:8px 0 0}
                    .head-actions{display:flex;gap:10px;flex-wrap:wrap}
                    .stats-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-bottom:16px}
                    .search-bar{display:grid;grid-template-columns:minmax(180px,1.4fr) repeat(4,minmax(120px,1fr)) auto;gap:10px;margin-bottom:16px;background:rgba(8,24,46,.68);border:1px solid rgba(125,211,252,.24);border-radius:8px;padding:12px;box-shadow:0 18px 60px rgba(0,0,0,.22)}
                    .stat-card,.panel,.command-card{background:rgba(8,24,46,.72);border:1px solid rgba(125,211,252,.22);border-radius:8px;box-shadow:0 18px 60px rgba(0,0,0,.22),inset 0 1px 0 rgba(255,255,255,.04);backdrop-filter:blur(14px)}
                    .stat-card{padding:18px;position:relative;overflow:hidden}
                    .stat-card:before{content:"";position:absolute;inset:0 auto 0 0;width:5px;background:#22d3ee;box-shadow:0 0 18px rgba(34,211,238,.55)}
                    .stat-card.accent-green:before{background:#38bdf8}
                    .stat-card.accent-orange:before{background:#60a5fa}
                    .stat-card span{display:block;color:#93aecd;font-size:13px;font-weight:800}
                    .stat-card strong{display:block;margin:8px 0 3px;font-size:28px;color:#f8fbff}
                    .stat-card small{color:#93aecd}
                    .command-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:16px}
                    .command-card{padding:18px;display:grid;grid-template-columns:minmax(180px,1fr) minmax(160px,260px) auto;align-items:end;gap:14px}
                    .command-card p{margin:7px 0 0;color:#93aecd;font-size:13px}
                    .panel{padding:18px;margin-bottom:16px}
                    .section-title{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:14px}
                    .section-title span{color:#93aecd;font-size:13px;font-weight:800}
                    .meta{color:#93aecd}
                    .path-line a{color:#bae6fd}.path-line span{margin:0 7px;color:#3b82f6}
                    .folder-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:10px}
                    .folder-card{display:grid;gap:10px;min-height:62px;border:1px solid rgba(125,211,252,.22);border-radius:8px;padding:12px;color:#dbeafe;background:rgba(2,8,23,.26);font-weight:800}
                    .folder-card:hover{border-color:rgba(34,211,238,.58);background:rgba(14,165,233,.12);transform:translateY(-1px)}
                    .folder-open{display:flex;align-items:center;gap:11px;color:#e0f2fe}
                    .folder-rename,.rename-form{display:flex;gap:6px;align-items:center}
                    .folder-icon{width:34px;height:28px;border-radius:6px;background:linear-gradient(135deg,#06b6d4,#2563eb);position:relative;box-shadow:0 0 18px rgba(34,211,238,.28),inset 0 -8px 0 rgba(255,255,255,.12)}
                    .folder-icon:before{content:"";position:absolute;left:3px;top:-5px;width:15px;height:8px;border-radius:5px 5px 0 0;background:#38bdf8}
                    .table-wrap{width:100%;overflow:auto;border:1px solid rgba(125,211,252,.22);border-radius:8px}
                    table{width:100%;border-collapse:separate;border-spacing:0;background:rgba(2,8,23,.3)}
                    th,td{text-align:left;border-bottom:1px solid rgba(125,211,252,.13);padding:12px;vertical-align:middle}
                    th{color:#93aecd;font-size:12px;background:rgba(14,165,233,.1);text-transform:uppercase;white-space:nowrap}
                    tr:last-child td{border-bottom:0}
                    .file-row:hover{background:rgba(14,165,233,.08)}
                    .file-row.selected{background:rgba(34,211,238,.14);outline:1px solid rgba(34,211,238,.45);outline-offset:-1px}
                    .file-name{display:flex;align-items:center;gap:12px;min-width:230px}
                    .file-name strong{display:block;color:#f8fbff;line-height:1.3}
                    .file-name small{display:block;margin-top:3px;color:#93aecd}
                    .tag-list{display:flex;gap:5px;flex-wrap:wrap;margin-top:6px}
                    .tag-badge{display:inline-flex;align-items:center;min-height:22px;border:1px solid rgba(34,211,238,.28);border-radius:999px;padding:2px 8px;background:rgba(14,165,233,.1);color:#bae6fd;font-size:12px}
                    .file-badge{display:grid;place-items:center;width:44px;height:44px;border-radius:8px;color:white;font-size:11px;font-weight:900}
                    .tone-image{background:#0ea5e9}.tone-video{background:#2563eb}.tone-text{background:#334155}.tone-doc{background:#1d4ed8}.tone-sheet{background:#0891b2}.tone-file{background:#475569}
                    .actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
                    .inline{display:inline}
                    .move-form{display:flex;gap:6px;align-items:center}
                    .move-form select{width:170px;min-height:34px;padding:6px 8px}
                    .rename-form input{width:160px;min-height:34px;padding:6px 8px}
                    .folder-rename input{min-height:34px;padding:6px 8px}
                    .folder-rename select{min-height:34px;padding:6px 8px}
                    .empty{color:#93aecd;text-align:center;padding:24px}
                    .small{text-align:left;margin:0}
                    .preview{overflow:auto;border:1px solid rgba(125,211,252,.22);border-radius:8px;background:rgba(2,8,23,.35);padding:14px}
                    .preview-media{max-width:100%;max-height:70vh;border-radius:8px;background:#111}
                    .text-preview{white-space:pre-wrap;background:#111827;color:#dbeafe;padding:16px;border-radius:8px;overflow:auto;margin:0}
                    .doc-preview{line-height:1.75;max-width:780px;background:rgba(2,8,23,.45);padding:18px;border-radius:8px}
                    .sheet td{min-width:90px}
                    .share-url{background:rgba(2,8,23,.42);border:1px dashed rgba(125,211,252,.35);border-radius:8px;padding:14px;word-break:break-all}
                    .share-path{font-family:Consolas,monospace;color:#93f4ff}
                    .context-menu{position:fixed;z-index:50;min-width:260px;background:rgba(4,15,31,.96);border:1px solid rgba(34,211,238,.42);border-radius:8px;padding:12px;box-shadow:0 24px 80px rgba(0,0,0,.42),0 0 30px rgba(34,211,238,.16);backdrop-filter:blur(16px)}
                    .context-menu form{display:grid;gap:10px}
                    .context-menu strong{color:#f8fbff}
                    .check{display:inline-flex;align-items:center;gap:4px;margin:0 10px;font-weight:400}
                    @media(max-width:940px){
                      .app-shell{grid-template-columns:1fr}
                      .sidebar{position:static;border-right:0;border-bottom:1px solid rgba(125,211,252,.18);flex-direction:row;align-items:center;flex-wrap:wrap}
                      .side-nav{display:flex;flex-wrap:wrap}.external-search{width:min(360px,100%)}.storage-note{display:none}
                      .stats-grid,.command-grid{grid-template-columns:1fr}
                      .command-card{grid-template-columns:1fr}
                      .search-bar{grid-template-columns:1fr}
                    }
                    @media(max-width:640px){
                      .topbar{height:auto;align-items:flex-start;gap:12px;flex-direction:column;padding:16px}
                      main{padding:18px}
                      .page-head,.section-title{align-items:flex-start;flex-direction:column}
                      .user-tools{width:100%;justify-content:space-between}
                    }
                  </style>
                """;
        if (user == null) {
            return """
                    <!doctype html>
                    <html lang="zh-CN">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>%s</title>
                      %s
                    </head>
                    <body class="auth-page">
                      <main class="auth-shell">%s</main>
                    </body>
                    </html>
                    """.formatted(esc(title), style, body);
        }
        String fileActive = title.startsWith("文件") || title.startsWith("预览") ? "active" : "";
        String shareActive = title.startsWith("分享管理") ? "active" : "";
        String trashActive = title.startsWith("回收站") ? "active" : "";
        String userActive = title.startsWith("用户管理") ? "active" : "";
        String adminNav = "ADMIN".equals(user.role) ? "<a class=\"" + userActive + "\" href=\"/admin/users\">用户管理</a>" : "";
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  %s
                </head>
                <body>
                  <div class="app-shell">
                    <aside class="sidebar">
                      <a class="side-brand" href="/files">
                        <span class="brand-mark">FM</span>
                        <span><strong>文件管理</strong><span>Java Cloud Disk</span></span>
                      </a>
                      <nav class="side-nav">
                        <a class="%s" href="/files">文件中心</a>
                        <a class="%s" href="/shares">分享管理</a>
                        <a class="%s" href="/trash">回收站</a>
                        %s
                      </nav>
                      <form class="external-search" action="https://www.baidu.com/s" method="get" target="_blank" rel="noopener noreferrer">
                        <label for="external-search-keyword">&#32593;&#32476;&#25628;&#32034;</label>
                        <div class="external-search-row">
                          <input id="external-search-keyword" type="search" name="wd" placeholder="Baidu" autocomplete="off" required>
                          <button class="external-search-button" type="submit" title="Baidu search" aria-label="Baidu search">&#128269;</button>
                        </div>
                      </form>
                      <div class="storage-note"><span>运行环境</span><strong>Java 17 HttpServer</strong></div>
                    </aside>
                    <div class="content-shell">
                      <header class="topbar">
                        <div class="topbar-title">%s</div>
                        <div class="user-tools">
                          <span class="user-pill"><span class="role-dot"></span>%s</span>
                          <a class="ghost-action" href="/logout">退出</a>
                        </div>
                      </header>
                      <main>%s</main>
                    </div>
                  </div>
                  <script>
                    (() => {
                      const menu = document.getElementById('file-context-menu');
                      const form = document.getElementById('context-move-form');
                      if (!menu || !form) return;
                      const hide = () => {
                        menu.hidden = true;
                        document.querySelectorAll('.file-row.selected').forEach(row => row.classList.remove('selected'));
                      };
                      document.addEventListener('contextmenu', event => {
                        const row = event.target.closest('.file-row');
                        if (!row || !row.dataset.fileId) return;
                        event.preventDefault();
                        document.querySelectorAll('.file-row.selected').forEach(item => item.classList.remove('selected'));
                        row.classList.add('selected');
                        form.action = '/files/move/' + encodeURIComponent(row.dataset.fileId);
                        menu.hidden = false;
                        const width = menu.offsetWidth || 260;
                        const height = menu.offsetHeight || 180;
                        const left = Math.min(event.clientX, window.innerWidth - width - 12);
                        const top = Math.min(event.clientY, window.innerHeight - height - 12);
                        menu.style.left = Math.max(12, left) + 'px';
                        menu.style.top = Math.max(12, top) + 'px';
                      });
                      document.addEventListener('click', event => {
                        if (!menu.hidden && !menu.contains(event.target)) hide();
                      });
                      document.addEventListener('keydown', event => {
                        if (event.key === 'Escape') hide();
                      });
                    })();
                  </script>
                </body>
                </html>
                """.formatted(esc(title), style, fileActive, shareActive, trashActive, adminNav, esc(title), esc(user.username), body);
    }

    private void loadUsers() throws IOException {
        if (!Files.exists(USERS_FILE)) {
            return;
        }
        for (String line : Files.readAllLines(USERS_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            if (p.length >= 6) {
                users.put(p[0], new User(p[0], p[1], p[2], p[3], Boolean.parseBoolean(p[4]), Instant.parse(p[5])));
            }
        }
    }

    private void saveUsers() throws IOException {
        List<String> lines = users.values().stream()
                .map(u -> String.join("\t", u.id, u.username, u.passwordHash, u.role, String.valueOf(u.enabled), u.createdAt.toString()))
                .toList();
        Files.write(USERS_FILE, lines, StandardCharsets.UTF_8);
    }

    private void loadFiles() throws IOException {
        if (!Files.exists(FILES_FILE)) {
            return;
        }
        boolean repaired = false;
        for (String line : Files.readAllLines(FILES_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            String originalName = repairMojibake(p.length > 2 ? p[2] : "");
            repaired = repaired || !originalName.equals(p.length > 2 ? p[2] : "");
            if (p.length >= 11) {
                files.put(p[0], new StoredFile(p[0], p[1], originalName, p[3], p[4], Long.parseLong(p[5]),
                        Instant.parse(p[6]), parseInstantOr(p[7], Instant.parse(p[6])), p[8], p[9], parseNullableInstant(p[10])));
            } else if (p.length >= 8) {
                Instant createdAt = Instant.parse(p[6]);
                files.put(p[0], new StoredFile(p[0], p[1], originalName, p[3], p[4], Long.parseLong(p[5]), createdAt, createdAt, p[7], "", null));
            } else if (p.length >= 7) {
                Instant createdAt = Instant.parse(p[6]);
                files.put(p[0], new StoredFile(p[0], p[1], originalName, p[3], p[4], Long.parseLong(p[5]), createdAt, createdAt, "", "", null));
            }
        }
        if (repaired) {
            saveFiles();
        }
    }

    private void saveFiles() throws IOException {
        List<String> lines = files.values().stream()
                .map(f -> String.join("\t", f.id, f.ownerId, f.originalName, f.storedName, f.contentType,
                        String.valueOf(f.size), f.createdAt.toString(), f.updatedAt.toString(), f.folderId,
                        f.tags == null ? "" : f.tags, f.deletedAt == null ? "" : f.deletedAt.toString()))
                .toList();
        Files.write(FILES_FILE, lines, StandardCharsets.UTF_8);
    }

    private void loadFolders() throws IOException {
        if (!Files.exists(FOLDERS_FILE)) {
            return;
        }
        for (String line : Files.readAllLines(FOLDERS_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            if (p.length >= 7) {
                folders.put(p[0], new Folder(p[0], p[1], p[2], p[3], Instant.parse(p[4]), parseInstantOr(p[5], Instant.parse(p[4])), parseNullableInstant(p[6])));
            } else if (p.length >= 5) {
                Instant createdAt = Instant.parse(p[4]);
                folders.put(p[0], new Folder(p[0], p[1], p[2], p[3], createdAt, createdAt, null));
            }
        }
    }

    private void saveFolders() throws IOException {
        List<String> lines = folders.values().stream()
                .map(f -> String.join("\t", f.id, f.ownerId, f.parentId, f.name, f.createdAt.toString(),
                        f.updatedAt.toString(), f.deletedAt == null ? "" : f.deletedAt.toString()))
                .toList();
        Files.write(FOLDERS_FILE, lines, StandardCharsets.UTF_8);
    }

    private void loadShares() throws IOException {
        if (!Files.exists(SHARES_FILE)) {
            return;
        }
        for (String line : Files.readAllLines(SHARES_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            if (p.length >= 4) {
                shares.put(p[0], new ShareLink(p[0], p[1], p[2], Instant.parse(p[3])));
            }
        }
    }

    private void saveShares() throws IOException {
        List<String> lines = shares.values().stream()
                .map(s -> String.join("\t", s.token, s.fileId, s.ownerId, s.createdAt.toString()))
                .toList();
        Files.write(SHARES_FILE, lines, StandardCharsets.UTF_8);
    }

    private static String contentType(String name, String fallback) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".html")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return fallback == null || fallback.isBlank() ? "application/octet-stream" : fallback;
    }

    private static Instant parseInstantOr(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Instant parseNullableInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate fileLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static boolean matchesFrom(Instant instant, String from) {
        if (from == null || from.isBlank()) {
            return true;
        }
        try {
            return !fileLocalDate(instant).isBefore(LocalDate.parse(from));
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean matchesTo(Instant instant, String to) {
        if (to == null || to.isBlank()) {
            return true;
        }
        try {
            return !fileLocalDate(instant).isAfter(LocalDate.parse(to));
        } catch (Exception e) {
            return true;
        }
    }

    private boolean contentMatches(StoredFile f, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        try {
            String text = searchableText(f);
            return text.toLowerCase(Locale.ROOT).contains(lower);
        } catch (Exception e) {
            return false;
        }
    }

    private String searchableText(StoredFile f) throws IOException {
        Path path = STORAGE_DIR.resolve(f.storedName);
        String lower = f.originalName.toLowerCase(Locale.ROOT);
        if (f.contentType.startsWith("text/") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".log")) {
            return readText(path);
        }
        if (lower.endsWith(".docx")) {
            return docxPreview(path).replaceAll("<[^>]+>", " ");
        }
        if (lower.endsWith(".xlsx")) {
            return xlsxPreview(path).replaceAll("<[^>]+>", " ");
        }
        return f.originalName + " " + f.contentType + " " + f.tags;
    }

    private static List<String> tagList(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return out;
        }
        for (String item : tags.split(",")) {
            String tag = item.trim();
            if (!tag.isBlank()) {
                out.add(tag);
            }
        }
        return out;
    }

    private static String cleanTags(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        for (String item : value.split("[,，\\s]+")) {
            String tag = item.trim().replaceAll("[\\r\\n\\t,，]", "");
            if (!tag.isBlank() && tags.stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                tags.add(tag.length() > 24 ? tag.substring(0, 24) : tag);
            }
            if (tags.size() >= 12) {
                break;
            }
        }
        return String.join(",", tags);
    }

    private static String tagBadges(String tags) {
        List<String> values = tagList(tags);
        if (values.isEmpty()) {
            return "<span class=\"meta\">无标签</span>";
        }
        StringBuilder out = new StringBuilder();
        for (String tag : values) {
            out.append("<a class=\"tag-badge\" href=\"/files?tag=")
                    .append(urlEncode(tag))
                    .append("\">")
                    .append(esc(tag))
                    .append("</a>");
        }
        return out.toString();
    }

    private static String safeFilename(String name) {
        String cleaned = name == null || name.isBlank() ? "upload.bin" : Path.of(name).getFileName().toString();
        cleaned = cleaned.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    private static String cleanFolderName(String name) {
        String cleaned = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String repairMojibake(String value) {
        if (value == null || value.isBlank() || !looksLikeUtf8Mojibake(value)) {
            return value;
        }
        try {
            String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return containsCjk(repaired) ? repaired : value;
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf')) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeUtf8Mojibake(String value) {
        return value.indexOf('Ã') >= 0 || value.indexOf('Â') >= 0 || value.indexOf('å') >= 0
                || value.indexOf('æ') >= 0 || value.indexOf('ç') >= 0 || value.indexOf('\u0080') >= 0
                || value.indexOf('\u0081') >= 0 || value.indexOf('\u0082') >= 0
                || value.indexOf('\u0083') >= 0 || value.indexOf('\u0084') >= 0
                || value.indexOf('\u0085') >= 0 || value.indexOf('\u0086') >= 0
                || value.indexOf('\u0087') >= 0 || value.indexOf('\u0088') >= 0
                || value.indexOf('\u0089') >= 0 || value.indexOf('\u008A') >= 0
                || value.indexOf('\u008B') >= 0 || value.indexOf('\u008C') >= 0
                || value.indexOf('\u008D') >= 0 || value.indexOf('\u008E') >= 0
                || value.indexOf('\u008F') >= 0 || value.indexOf('\u0090') >= 0
                || value.indexOf('\u0091') >= 0 || value.indexOf('\u0092') >= 0
                || value.indexOf('\u0093') >= 0 || value.indexOf('\u0094') >= 0
                || value.indexOf('\u0095') >= 0 || value.indexOf('\u0096') >= 0
                || value.indexOf('\u0097') >= 0 || value.indexOf('\u0098') >= 0
                || value.indexOf('\u0099') >= 0 || value.indexOf('\u009A') >= 0
                || value.indexOf('\u009B') >= 0 || value.indexOf('\u009C') >= 0
                || value.indexOf('\u009D') >= 0 || value.indexOf('\u009E') >= 0
                || value.indexOf('\u009F') >= 0;
    }

    private static String extension(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static String humanSize(long size) {
        if (size < 1024) return size + " B";
        double kb = size / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private static String errorBlock(String error) {
        return error == null ? "" : "<p class=\"error\">" + esc(error) + "</p>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Optional<String> queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            if (urlDecode(key).equals(name)) {
                return Optional.of(urlDecode(value));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static boolean looksUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String lastPathPart(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String safeHeader(String filename) {
        return filename.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static void html(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private interface ExchangeHandler {
        void handle(HttpExchange ex) throws Exception;
    }

    private record Session(String userId, Instant expiresAt) {}

    private record MultipartFile(String filename, String contentType, byte[] bytes) {}

    private record ShareLink(String token, String fileId, String ownerId, Instant createdAt) {}

    private static class User {
        final String id;
        final String username;
        final String passwordHash;
        String role;
        boolean enabled;
        final Instant createdAt;

        User(String id, String username, String passwordHash, String role, boolean enabled, Instant createdAt) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.role = role;
            this.enabled = enabled;
            this.createdAt = createdAt;
        }
    }

    private record ClipboardItem(String kind, String id, String mode) {}

    private record Folder(String id, String ownerId, String parentId, String name,
                          Instant createdAt, Instant updatedAt, Instant deletedAt) {}

    private record StoredFile(String id, String ownerId, String originalName, String storedName,
                              String contentType, long size, Instant createdAt, Instant updatedAt,
                              String folderId, String tags, Instant deletedAt) {}

    private static class Passwords {
        static String hash(String password) throws Exception {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt);
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        }

        static boolean verify(String password, String stored) throws Exception {
            String[] parts = stored.split(":");
            if (parts.length != 2) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = pbkdf2(password.toCharArray(), salt);
            if (expected.length != actual.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < expected.length; i++) {
                diff |= expected[i] ^ actual[i];
            }
            return diff == 0;
        }

        private static byte[] pbkdf2(char[] password, byte[] salt) throws Exception {
            KeySpec spec = new PBEKeySpec(password, salt, 65_536, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        }
    }
}
