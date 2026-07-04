package br.upe.urlshortener;

import br.upe.common.Config;
import br.upe.common.http.HttpRequest;
import br.upe.common.http.HttpResponse;
import br.upe.common.http.HttpServer;
import br.upe.urlshortener.repository.UserRepository;
import br.upe.urlshortener.service.UrlService;
import br.upe.urlshortener.service.UserService;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

public class URLShortenerApplication {
    private static final Logger logger = Logger.getLogger(URLShortenerApplication.class.getName());

    // Remove the UserRepository and add UserService
    private static final UrlService urlService = new UrlService();
    private static final UserService userService = new UserService();
    private static final String DOMAIN = Config.getString("BASE_DOMAIN", "http://localhost:8080/");

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    public static void main(String[] args) throws Exception {
        int requestedPort = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        HttpServer app = new HttpServer();

        // ---------------------------------------------------------
        // ROUTE REGISTRY
        // ---------------------------------------------------------
        app.post("/api/v1/users", URLShortenerApplication::handleCreateUser); // NEW: Generate API Keys
        app.post("/api/v1/shorten", URLShortenerApplication::handleShorten);
        app.delete("*", URLShortenerApplication::handleDelete);
        app.get("*", URLShortenerApplication::handleGet);

        // ---------------------------------------------------------
        // SERVER START
        // ---------------------------------------------------------
        app.listen(requestedPort, (actualPort) -> {
            logger.info("Shortener Service rodando com ExpressApp na porta: " + actualPort);
            startHeartbeatTask(actualPort);
        });
    }

    // =========================================================
    // ROUTE HANDLERS (CONTROLLER LOGIC)
    // =========================================================

    private static void handleCreateUser(HttpRequest req, HttpResponse res) throws IOException {
        String username = extractJsonField(req.getBody(), "username");

        if (username == null || username.isBlank()) {
            res.status(400).json("{\"error\":\"Missing username\"}");
            return;
        }

        try {
            // Delegate business logic (creating user + generating key) to the Service
            String apiKey = userService.registerUser(username);
            String jsonResponse = String.format("{\"username\":\"%s\", \"api_key\":\"%s\"}", username, apiKey);
            res.status(201).json(jsonResponse);
            logger.info("Novo usuário registrado: " + username);
        } catch (Exception e) {
            logger.severe("Erro ao criar usuário: " + e.getMessage());
            res.status  (500).json("{\"error\":\"Database Error\"}");
        }
    }

    private static void handleShorten(HttpRequest req, HttpResponse res)throws IOException {
        try {
            // 1. Authenticate before allowing creation
            Integer userId = getAuthenticatedUser(req, res);
            if (userId == null) {
                return; // 401 already handled
            }

            String originalUrl = extractJsonField(req.getBody(), "url");

            if (originalUrl == null || originalUrl.isBlank()) {
                res.status(400).json("{\"error\":\"Missing url\"}");
                return;
            }

            // 2. Pass userId to the service to establish ownership
            String code = urlService.shortenUrl(originalUrl, Integer.valueOf(userId));
            String jsonResponse = String.format("{\"newUrl\":\"%s%s\"}", DOMAIN, code);
            res.status(201).json(jsonResponse);
            logger.info("URL Encurtada salva no DB: " + originalUrl + " -> " + code + " (User: " + userId + ")");

        } catch (Exception e) {
            logger.severe("Erro interno do servidor: " + e.getMessage());
            res.status(500).json("{\"error\":\"Database Error\"}");
        }
    }

    private static void handleDelete(HttpRequest req, HttpResponse res) throws IOException {
        try {
            // 1. Authenticate
            Integer userId = getAuthenticatedUser(req, res);
            if (userId == null) {
                return;
            }

            String prefix = "/api/v1/shorten/";
            String path = req.getPath();

            if (!path.startsWith(prefix)) {
                res.status(404).json("{\"error\":\"Route not found\"}");
                return;
            }

            String shortcode = path.substring(prefix.length());
            if (shortcode.isBlank()) {
                res.status(400).json("{\"error\":\"Missing shortcode\"}");
                return;
            }

            // 2. Authorization Check: Is this user the creator?
            if (!urlService.isOwner(shortcode, userId)) {
                res.status(403).json("{\"error\":\"Forbidden: You do not own this shortcode\"}");
                return;
            }

            // 3. Delete
            boolean deleted = urlService.deleteUrl(shortcode);
            if (!deleted) {
                res.status(404).json("{\"error\":\"Code not found\"}");
                return;
            }

            res.status(200).json("{\"message\":\"Link deleted\"}");
            logger.info("URL excluída: " + shortcode);

        } catch (Exception e) {
            logger.severe("Erro interno do servidor: " + e.getMessage());
            res.status(500).json("{\"error\":\"Database Error\"}");
        }
    }

    private static void handleGet(HttpRequest req, HttpResponse res) throws IOException{
        String path = req.getPath();

        if (path.startsWith("/stats/")) {
            handleMetrics(req, path, res);
        } else {
            handleRedirect(path, res);
        }
    }

    private static void handleMetrics(HttpRequest req, String path, HttpResponse res) throws IOException{
        try {
            Integer userId = getAuthenticatedUser(req, res);
            if (userId == null) {
                return;
            }

            String code = path.substring("/stats/".length());
            if (code.isBlank()) {
                res.status(400).json("{\"error\":\"Missing shortcode\"}");
                return;
            }

            if (!urlService.isOwner(code, userId)) {
                res.status(403).json("{\"error\":\"Forbidden: You do not own this shortcode\"}");
                return;
            }

            Optional<String> urlOpt = urlService.getOriginalUrl(code);
            if (urlOpt.isEmpty()) {
                res.status(404).json("{\"error\":\"Code not found\"}");
                return;
            }

            long count = urlService.getAccessCount(code);
            String json = String.format(
                    "{\"shortcode\":\"%s\",\"original_url\":\"%s\",\"access_count\":%d}",
                    code, urlOpt.get(), count
            );
            res.status(200).json(json);

        } catch (Exception e) {
            logger.severe("Erro ao buscar métricas: " + e.getMessage());
            res.status(500).json("{\"error\":\"Database Error\"}");
        }
    }

    private static void handleRedirect(String path, HttpResponse res) throws IOException {
        String code = path.substring(1);

        if (code.isBlank()) {
            res.status(400).json("{\"error\":\"Missing shortcode\"}");
            return;
        }

        try {
            Optional<String> urlOpt = urlService.getOriginalUrl(code);

            if (urlOpt.isEmpty()) {
                res.status(404).json("{\"error\":\"Code not found\"}");
                return;
            }

            String originalUrl = urlOpt.get();
            logger.info("Redirecionando " + code + " para " + originalUrl);
            urlService.registerAccessAsync(code);
            res.redirect(originalUrl);

        } catch (Exception e) {
            logger.severe("Erro interno do servidor: " + e.getMessage());
            res.status(500).json("{\"error\":\"Database Error\"}");
        }
    }

    // =========================================================
    // ACCESS CONTROL
    // =========================================================

    private static Integer getAuthenticatedUser(HttpRequest req, HttpResponse res) throws Exception {
        String authorization = req.getHeader("authorization");
        if (authorization == null || authorization.isBlank()) {
            res.status(401).json("{\"error\":\"Unauthorized\"}");
            return null;
        }

        String apiKey = extractApiKeyFromAuthorization(authorization);
        if (apiKey == null || apiKey.isBlank()) {
            res.status(401).json("{\"error\":\"Unauthorized\"}");
            return null;
        }

        // Delegate the identity check to the Service
        Integer userId = userService.authenticateByApiKey(apiKey);
        if (userId == null) {
            res.status(401).json("{\"error\":\"Unauthorized\"}");
            return null;
        }

        return userId;
    }

    private static String extractApiKeyFromAuthorization(String authorizationHeader) {
        String header = authorizationHeader.trim();
        if (header.toLowerCase().startsWith("bearer ")) {
            return header.substring(7).trim();
        }
        return header;
    }

    // =========================================================
    // UTILITIES
    // =========================================================

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();

        int quoteStart = json.indexOf("\"", start);
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteStart == -1 || quoteEnd == -1) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }

    // =========================================================
    // HEARTBEAT / DISCOVERY
    // =========================================================

    private static void startHeartbeatTask(int port) {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    registerInNameRegistry(port);
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Falha ao renovar registro. Tentando novamente em breve...");
                    try { Thread.sleep(Duration.ofSeconds(5)); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    private static void registerInNameRegistry(int port) {
        try (var socket = new Socket(Config.getString("REGISTRY_HOST", "localhost"), Config.getInt("REGISTRY_PORT", 9000));
             var out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("REGISTER app-service localhost:" + port);
            logger.fine("Registro renovado no Name Registry (Porta " + port + ")");
        } catch (Exception e) {
            logger.warning("Não foi possível registrar no Name Registry. Ele está online?");
        }
    }
}