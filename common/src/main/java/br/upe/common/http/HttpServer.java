package br.upe.common.http;

import br.upe.common.Config;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

public class HttpServer {
    private static final Logger logger = Logger.getLogger(HttpServer.class.getName());

    // Armazena as rotas no formato "MÉTODO /caminho" -> Handler
    private final Map<String, RouteHandler> routes = new HashMap<>();

    // Infrastructure state
    private final String serviceName;
    private final String baseDomain;

    public HttpServer(String serviceName) {
        this.serviceName = serviceName;
        // The server resolves its own port and domain configuration
        this.baseDomain = Config.getString("BASE_DOMAIN", "http://localhost:8080/");
    }

    public String getBaseDomain() {
        return this.baseDomain;
    }

    // Métodos estilo Express para registrar rotas
    public void get(String path, RouteHandler handler) {
        routes.put("GET " + path, handler);
    }

    public void post(String path, RouteHandler handler) {
        routes.put("POST " + path, handler);
    }

    public void delete(String path, RouteHandler handler) {
        routes.put("DELETE " + path, handler);
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int actualPort = serverSocket.getLocalPort();
            logger.info(serviceName + " rodando isolado da rede na porta: " + actualPort);

            // Inicia o heartbeat na rede distribuída automaticamente
            startHeartbeatTask(actualPort);

            while (true) {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleConnection(client));
            }
        }
    }

    private void handleConnection(Socket client) {
        try (client; var out = client.getOutputStream()) {
            // Realiza o parser do fluxo de entrada para um objeto HttpRequest
            HttpRequest req = HttpParser.parse(client.getInputStream());

            if (req == null) return;

            HttpResponse res = new HttpResponse(out);

            // Tenta buscar a rota pela correspondência exata primeiro (ex: "POST /api/v1/shorten")
            String routeKey = req.getMethod() + " " + req.getPath();
            RouteHandler handler = routes.get(routeKey);

            // Fallback: Se não encontrou a rota exata, busca por uma rota curinga ("*")
            // Exemplo: "GET *" pegará tudo que for GET e não estiver mapeado.
            if (handler == null) {
                handler = routes.get(req.getMethod() + " *");
            }

            if (handler != null) {
                try {
                    handler.handle(req, res);
                } catch (Exception e) {
                    logger.severe("Erro interno na rota: " + e.getMessage());
                    res.status(500).json("{\"error\":\"Internal Server Error\"}");
                }
            } else {
                res.status(404).json("{\"error\":\"Route not found\"}");
            }

        } catch (IOException e) {
            logger.warning("Erro de I/O no socket: " + e.getMessage());
        }
    }

    // =========================================================
    // HEARTBEAT / DISCOVERY (HIDDEN FROM APPLICATION)
    // =========================================================

    private void startHeartbeatTask(int port) {
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

    private void registerInNameRegistry(int port) {
        String registryHost = Config.getString("REGISTRY_HOST", "localhost");
        int registryPort = Config.getInt("REGISTRY_PORT", 9000);

        try (var socket = new Socket(registryHost, registryPort);
             var out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("REGISTER " + serviceName + " localhost:" + port);
            logger.fine("Registro renovado no Name Registry (Porta " + port + ")");
        } catch (Exception e) {
            logger.warning("Não foi possível registrar no Name Registry. Ele está online?");
        }
    }
}
