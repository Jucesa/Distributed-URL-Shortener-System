package br.upe.urlshortener;

import br.upe.common.Base62;
import br.upe.common.Config;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * API de Encurtamento de URL usando Sockets TCP.
 * Fornece endpoints para encurtar (POST) e redirecionar (GET).
 */
public class URLShortenerApplication {
    private static final Logger logger = Logger.getLogger(URLShortenerApplication.class.getName());

    // Bootstrapping: Inicia em 14 milhões conforme requisito
    private static final AtomicLong idCounter = new AtomicLong(Config.getInt("START_ID", 14_000_000));
    private static final Map<String, String> urlDatabase = new ConcurrentHashMap<>();

    private static final int PORT = Config.getInt("SHORTENER_PORT", 8081);
    private static final String DOMAIN = Config.getString("BASE_DOMAIN", "http://localhost:8080/");

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    public static void main(String[] args) throws IOException {
        registerInNameRegistry();

        try (var serverSocket = new ServerSocket(PORT)) {
            logger.info("Shortener Service rodando na porta " + PORT);

            while (true) {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleRequest(client));
            }
        }
    }

    /**
     * Processa a requisição HTTP manual via Socket.
     */
    private static void handleRequest(Socket client) {
        try (client;
             var in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             var out = client.getOutputStream()) {

            String firstLine = in.readLine();
            if (firstLine == null) return;

            String[] parts = firstLine.split(" ");
            String method = parts[0];
            String path = parts[1];

            // Roteamento
            if (method.equals("POST") && path.equals("/api/v1/shorten")) {
                handleShorten(in, out);
            } else if (method.equals("GET")) {
                handleRedirect(path.substring(1), out);
            } else {
                out.write(HttpProtocol.createResponse(404, "Not Found", "{\"error\":\"Not Found\"}").getBytes());
            }

        } catch (Exception e) {
            logger.severe("Erro na requisição: " + e.getMessage());
        }
    }

    /**
     * Endpoint POST: Encurta a URL.
     */
    private static void handleShorten(BufferedReader in, OutputStream out) throws IOException {
        // Ler Headers para achar Content-Length
        int contentLength = 0;
        String line;
        while (!(line = in.readLine()).isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }

        // Ler Body
        char[] bodyChars = new char[contentLength];
        in.read(bodyChars);
        String body = new String(bodyChars);

        String originalUrl = HttpProtocol.unmarshalJsonField(body, "url");
        if (originalUrl == null) {
            out.write(HttpProtocol.createResponse(400, "Bad Request", "{\"error\":\"Missing url\"}").getBytes());
            return;
        }

        String code = Base62.encode(idCounter.incrementAndGet());
        urlDatabase.put(code, originalUrl);

        String jsonResponse = String.format("{\"newUrl\":\"%s%s\"}", DOMAIN, code);
        out.write(HttpProtocol.createResponse(201, "Created", jsonResponse).getBytes());
        logger.info("URL Encurtada: " + originalUrl + " -> " + code);
    }

    /**
     * Endpoint GET: Redireciona via 302.
     */
    private static void handleRedirect(String code, OutputStream out) throws IOException {
        String originalUrl = urlDatabase.get(code);

        if (originalUrl == null) {
            out.write(HttpProtocol.createResponse(404, "Not Found", "{\"error\":\"Code not found\"}").getBytes());
            return;
        }

        logger.info("Redirecionando " + code + " para " + originalUrl);
        String response = HttpProtocol.createResponse(302, "Found", "", "Location: " + originalUrl);
        out.write(response.getBytes());
    }

    /**
     * Registra este serviço no Name Registry para que o Load Balancer o encontre.
     */
    private static void registerInNameRegistry() {
        try (var socket = new Socket(Config.getString("REGISTRY_HOST", "localhost"),
                Config.getInt("REGISTRY_PORT", 9000));
             var out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("REGISTER app-service localhost:" + PORT);
            logger.info("Registrado no Name Registry com sucesso.");
        } catch (Exception e) {
            logger.warning("Não foi possível registrar no Name Registry.");
        }
    }
}