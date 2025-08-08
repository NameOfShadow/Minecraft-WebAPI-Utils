//package com.yourpackage.web;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class WebAPI {
    private final JavaPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private final Map<String, byte[]> staticFiles = new ConcurrentHashMap<>();
    private final Map<String, DynamicRoute> dynamicRoutes = new ConcurrentHashMap<>();
    private final Map<String, SSEEndpoint> sseEndpoints = new ConcurrentHashMap<>();

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=UTF-8",
            ".css", "text/css; charset=UTF-8",
            ".js", "application/javascript; charset=UTF-8",
            ".json", "application/json; charset=UTF-8",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".ico", "image/x-icon"
    );

    public WebAPI(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // Запуск сервера
    public void start(int port, int threadPoolSize) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Web server already running");
        }

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(executor);

        server.createContext("/", this::handleRequest);
        server.start();

        plugin.getLogger().info("🚀 Web server started on port " + port);
    }

    // Остановка сервера
    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("🛑 Web server stopped");
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        closeAllSSEConnections();
    }

    // Загрузка статических файлов
    public void loadStaticFiles(String... filenames) {
        for (String filename : filenames) {
            loadStaticFile(filename);
        }
    }

    private void loadStaticFile(String filename) {
        String resourcePath = "web/" + filename;
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                plugin.getLogger().warning("Static file not found: " + resourcePath);
                return;
            }

            byte[] content = is.readAllBytes();
            staticFiles.put("/" + filename, content);
            plugin.getLogger().info("📄 Loaded static file: " + filename);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load static file " + filename + ": " + e.getMessage());
        }
    }

    // Регистрация динамических маршрутов
    public void addDynamicRoute(String path, Supplier<byte[]> contentSupplier, String contentType) {
        dynamicRoutes.put(path, new DynamicRoute(contentSupplier, contentType));
    }

    public void addDynamicRoute(String path, Supplier<byte[]> contentSupplier) {
        addDynamicRoute(path, contentSupplier, "application/json");
    }

    // Регистрация SSE эндпоинтов
    public void addSSEEndpoint(String path) {
        sseEndpoints.put(path, new SSEEndpoint());
    }

    // Трансляция данных в SSE эндпоинт
    public void broadcastToEndpoint(String path, String data) {
        SSEEndpoint endpoint = sseEndpoints.get(path);
        if (endpoint != null) {
            endpoint.broadcast(data);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = normalizePath(exchange.getRequestURI().getPath());
            String method = exchange.getRequestMethod();
            String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();

            // Логирование запроса
            plugin.getLogger().info("🌐 [" + clientIP + "] " + method + " " + path);

            // Обработка CORS
            setCorsHeaders(exchange);

            // Предварительный ответ для OPTIONS
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, new byte[0], "text/plain");
                return;
            }

            // Обработка корневого пути
            if ("/".equals(path)) {
                path = staticFiles.containsKey("/index.html")
                        ? "/index.html"
                        : "/";
            }

            // Обработка favicon.ico
            if ("/favicon.ico".equals(path)) {
                byte[] empty = new byte[0];
                sendResponse(exchange, 204, empty, "image/x-icon");
                return;
            }

            // 1. Проверка SSE эндпоинтов
            SSEEndpoint sseEndpoint = sseEndpoints.get(path);
            if (sseEndpoint != null && "GET".equalsIgnoreCase(method)) {
                handleSSE(exchange, sseEndpoint);
                return;
            }

            // 2. Проверка динамических маршрутов
            DynamicRoute dynamicRoute = dynamicRoutes.get(path);
            if (dynamicRoute != null) {
                if ("POST".equalsIgnoreCase(method)) {
                    dynamicRoute.handlePost(exchange);
                } else {
                    byte[] content = dynamicRoute.getContent();
                    sendResponse(exchange, 200, content, dynamicRoute.getContentType());
                }
                return;
            }

            // 3. Проверка статических файлов
            byte[] staticContent = staticFiles.get(path);
            if (staticContent != null) {
                String contentType = determineContentType(path);
                sendResponse(exchange, 200, staticContent, contentType);
                return;
            }

            // Если ничего не найдено
            String notFound = "404 Not Found: " + path;
            sendResponse(exchange, 404, notFound.getBytes(StandardCharsets.UTF_8), "text/plain");

        } catch (Exception e) {
            plugin.getLogger().severe("⚠️ Request handling error: " + e.getMessage());
            sendResponse(exchange, 500, "Internal Server Error".getBytes(StandardCharsets.UTF_8), "text/plain");
        }
    }

    // Обработка SSE соединений
    private void handleSSE(HttpExchange exchange, SSEEndpoint endpoint) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);

        SSEConnection connection = new SSEConnection(exchange);
        endpoint.addConnection(connection);

        // Отправляем начальное сообщение
        String initEvent = "event: init\n";
        initEvent += "data: {\"status\":\"connected\"}\n\n";
        connection.send(initEvent.getBytes(StandardCharsets.UTF_8));
    }

    // Вспомогательные методы
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private String determineContentType(String path) {
        return CONTENT_TYPES.entrySet().stream()
                .filter(e -> path.endsWith(e.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse("text/plain");
    }

    private void sendResponse(HttpExchange exchange, int code, byte[] data, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().add("Access-Control-Expose-Headers", "*");
        exchange.getResponseHeaders().add("Access-Control-Max-Age", "3600");
    }

    private void closeAllSSEConnections() {
        for (SSEEndpoint endpoint : sseEndpoints.values()) {
            endpoint.closeAll();
        }
    }

    // Внутренние классы
    private static class DynamicRoute {
        private final Supplier<byte[]> contentSupplier;
        private final String contentType;
        private Consumer<HttpExchange> postHandler;

        public DynamicRoute(Supplier<byte[]> contentSupplier, String contentType) {
            this.contentSupplier = contentSupplier;
            this.contentType = contentType;
        }

        // Конструктор для POST-обработчиков
        public DynamicRoute(Consumer<HttpExchange> postHandler) {
            this.contentSupplier = null;
            this.contentType = null;
            this.postHandler = postHandler;
        }

        public byte[] getContent() {
            assert contentSupplier != null;
            return contentSupplier.get();
        }

        public String getContentType() {
            return contentType;
        }

        public void handlePost(HttpExchange exchange) {
            if (postHandler != null) {
                postHandler.accept(exchange);
            }
        }
    }

    // Метод для регистрации POST-обработчиков
    public void addPostHandler(String path, Consumer<HttpExchange> handler) {
        dynamicRoutes.put(path, new DynamicRoute(handler));
    }

    public class SSEEndpoint {
        private final Set<SSEConnection> connections = ConcurrentHashMap.newKeySet();

        public void addConnection(SSEConnection connection) {
            connections.add(connection);
        }

        public void broadcast(String data) {
            if (data == null || data.isEmpty()) return;

            String event = "data: " + data + "\n\n";
            byte[] bytes = event.getBytes(StandardCharsets.UTF_8);

            // Удаляем нерабочие соединения и отправляем данные
            connections.removeIf(conn -> {
                if (conn.isClosed()) {
                    return true;
                }
                try {
                    conn.send(bytes);
                    return false;
                } catch (IOException e) {
                    return true;
                }
            });
        }

        public void closeAll() {
            connections.forEach(SSEConnection::close);
            connections.clear();
        }
    }

    private class SSEConnection {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final HttpExchange exchange;
        private final OutputStream outputStream;
        private final String clientId;

        public SSEConnection(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.outputStream = exchange.getResponseBody();
            this.clientId = UUID.randomUUID().toString();
            plugin.getLogger().info("🔌 SSE connection opened: " + clientId);
        }

        public void send(byte[] data) throws IOException {
            if (!closed.get()) {
                try {
                    outputStream.write(data);
                    outputStream.flush();
                } catch (IOException e) {
                    if (!closed.get()) {
                        plugin.getLogger().warning("🚫 Error sending SSE data to " + clientId + ": " + e.getMessage());
                        close();
                    }
                    throw e;
                }
            }
        }

        public boolean isClosed() {
            return closed.get();
        }

        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                } finally {
                    exchange.close();
                    plugin.getLogger().info("🔌 SSE connection closed: " + clientId);
                }
            }
        }
    }
}