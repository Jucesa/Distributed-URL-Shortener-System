package br.upe.urlshortener;

import br.upe.common.Config;
import br.upe.common.http.HttpServer;
import br.upe.urlshortener.service.UrlService;

import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Classe principal responsável por inicializar a API de Encurtamento de URL.
 * Agora utiliza o motor HTTP "ExpressApp" para roteamento elegante.
 *
 * @version 5.0 (Express-like Router Integration)
 */
public class URLShortenerApplication {
    private static final Logger logger = Logger.getLogger(URLShortenerApplication.class.getName());

    private static final UrlService urlService = new UrlService();
    private static final String DOMAIN = Config.getString("BASE_DOMAIN", "http://localhost:8080/");

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
        // ROTA GET: Redirecionamento Dinâmico (Catch-All)
        // ---------------------------------------------------------
        // Em um roteador completo teríamos app.get("/:code"), mas aqui
        // podemos interceptar qualquer rota GET que não foi mapeada.
        app.get("*", (req, res) -> {
            // Remove a barra inicial para pegar apenas o código (Ex: "/4k9Z" -> "4k9Z")
            String code = req.getPath().substring(1);

            try {
                Optional<String> urlOpt = urlService.getOriginalUrl(code);

                if (urlOpt.isEmpty()) {
                    res.status(404).json("{\"error\":\"Code not found\"}");
                    return;
                }

                String originalUrl = urlOpt.get();
                logger.info("Redirecionando " + code + " para " + originalUrl);
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

            // O Heartbeat agora registra a porta efêmera correta gerada pelo SO
            startHeartbeatTask(actualPort);

        });
    }

    /**
     * Mantém o serviço registrado no Name Registry continuamente.
     */
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

    /**
     * Dispara o comando de registro para o serviço central de descoberta.
     */
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