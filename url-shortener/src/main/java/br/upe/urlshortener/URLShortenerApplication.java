package br.upe.urlshortener;

import br.upe.common.Config;
import br.upe.common.http.HttpServer;
import br.upe.urlshortener.service.UrlService;

import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

public class URLShortenerApplication {
    private static final Logger logger = Logger.getLogger(URLShortenerApplication.class.getName());

    private static final UrlService urlService = new UrlService();
    private static final String DOMAIN = Config.getString("BASE_DOMAIN", "http://localhost:8080/");
    private static final br.upe.urlshortener.repository.UserRepository userRepository = new br.upe.urlshortener.repository.UserRepository();

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    public static void main(String[] args) throws Exception {
        int requestedPort = args.length > 0 ? Integer.parseInt(args[0]) : 0;

        HttpServer app = new HttpServer();

        // ---------------------------------------------------------
        // ROTA POST: Encurtar URL
        // ---------------------------------------------------------
        app.post("/api/v1/shorten", (req, res) -> {
            String originalUrl = extractJsonField(req.getBody(), "url");

            if (originalUrl == null) {
                res.status(400).json("{\"error\":\"Missing url\"}");
                return;
            }

            try {
                String code = urlService.shortenUrl(originalUrl);
                String jsonResponse = String.format("{\"newUrl\":\"%s%s\"}", DOMAIN, code);
                res.status(201).json(jsonResponse);
                logger.info("URL Encurtada salva no DB: " + originalUrl + " -> " + code);
            } catch (Exception e) {
                logger.severe("Erro interno do servidor: " + e.getMessage());
                res.status(500).json("{\"error\":\"Database Error\"}");
            }
        });

        // ---------------------------------------------------------
        // ROTA GET: Catch-all — métricas (/stats/CODE) e redirect
        // ROTA DELETE: Remove link protegido por API_KEY
        // ---------------------------------------------------------
        app.delete("*", (req, res) -> {
            if (!requireApiKey(req, res)) {
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

            try {
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
        });

        // ---------------------------------------------------------
        // ROTA GET: Redirecionamento Dinâmico (Catch-All)
        // ---------------------------------------------------------
        app.get("*", (req, res) -> {
            String path = req.getPath();

            // Se o path começa com /stats/, trata como métricas
            if (path.startsWith("/stats/")) {
                String code = path.substring("/stats/".length());

                if (code.isEmpty()) {
                    res.status(400).json("{\"error\":\"Missing shortcode\"}");
                    return;
                }

                try {
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
                return;
            }

            // Caso contrário, trata como redirecionamento
            String code = path.substring(1);

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
        });

        // ---------------------------------------------------------
        // START: Iniciando Servidor HTTP e Heartbeat
        // ---------------------------------------------------------
        app.listen(requestedPort, (actualPort) -> {
            logger.info("Shortener Service rodando com ExpressApp na porta: " + actualPort);
            startHeartbeatTask(actualPort);
        });
    }

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
        try (var socket = new Socket(Config.getString("REGISTRY_HOST", "localhost"),
                Config.getInt("REGISTRY_PORT", 9000));
             var out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("REGISTER app-service localhost:" + port);
            logger.fine("Registro renovado no Name Registry (Porta " + port + ")");
        } catch (Exception e) {
            logger.warning("Não foi possível registrar no Name Registry. Ele está online?");
        }
    }

    /**
     * Utilitário simples para extrair campos do JSON (Substitui o HttpProtocol.unmarshalJsonField)
     */
    private static boolean requireApiKey(br.upe.common.http.HttpRequest req, br.upe.common.http.HttpResponse res) throws Exception {
        String authorization = req.getHeader("authorization");
        if (authorization == null || authorization.isBlank()) {
            res.status(401).json("{\"error\":\"Unauthorized\"}");
            return false;
        }

        String apiKey = extractApiKeyFromAuthorization(authorization);
        if (apiKey == null || apiKey.isBlank() || !userRepository.isValidApiKey(apiKey)) {
            res.status(401).json("{\"error\":\"Unauthorized\"}");
            return false;
        }

        return true;
    }

    private static String extractApiKeyFromAuthorization(String authorizationHeader) {
        String header = authorizationHeader.trim();
        if (header.toLowerCase().startsWith("bearer ")) {
            return header.substring(7).trim();
        }
        return header;
    }

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
}