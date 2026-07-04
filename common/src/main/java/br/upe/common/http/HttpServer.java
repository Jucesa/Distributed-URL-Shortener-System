package br.upe.common.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

public class HttpServer {
    private static final Logger logger = Logger.getLogger(HttpServer.class.getName());

    // Armazena as rotas no formato "MÉTODO /caminho" -> Handler
    private final Map<String, RouteHandler> routes = new HashMap<>();

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

    public void listen(int port, IntConsumer onStart) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {

            // Pega a porta real. Se 'port' for 0, o SO define uma porta efêmera.
            int actualPort = serverSocket.getLocalPort();

            if (onStart != null) {
                // Passa a porta real de volta para a sua aplicação
                onStart.accept(actualPort);
            }

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
}
