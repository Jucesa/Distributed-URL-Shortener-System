package br.upe.urlshortener;

import br.upe.common.Config;
import br.upe.urlshortener.service.UrlService;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Classe principal responsável por inicializar a API de Encurtamento de URL.
 * <p>
 * Este serviço atua como um servidor HTTP customizado utilizando Sockets TCP puros.
 * Ele atua como a camada de "Controller" ou "Roteador", processando requisições HTTP
 * manualmente, extraindo cabeçalhos e roteando o fluxo de dados para a camada de
 * Serviço (Business Logic).
 * <p>
 * O servidor utiliza Virtual Threads (Java 21) para lidar com múltiplas conexões
 * concorrentes de forma leve e altamente escalável.
 *
 * @version 4.0 (Service Layer, DB Sequence & Heartbeat Integration)
 */
public class URLShortenerApplication {
    private static final Logger logger = Logger.getLogger(URLShortenerApplication.class.getName());

    /**
     * Instância do serviço que centraliza as regras de negócio de encurtamento.
     * Esta camada abstrai as operações de banco de dados e geração de códigos Base62,
     * mantendo o servidor HTTP focado apenas em receber requisições e enviar respostas.
     */
    private static final UrlService urlService = new UrlService();

    /**
     * Domínio base utilizado para formatar o link encurtado retornado ao usuário.
     */
    private static final String DOMAIN = Config.getString("BASE_DOMAIN", "http://localhost:8080/");

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    /**
     * Método de inicialização (Entry Point) do serviço de Encurtamento.
     * <p>
     * Este método solicita uma porta efêmera ao Sistema Operacional (ou utiliza a informada por argumento),
     * inicia a rotina de Heartbeat com o Name Registry e inicia o loop principal de escuta para novas conexões HTTP.
     *
     * @param args Argumentos de linha de comando. Opcionalmente, o primeiro argumento pode forçar uma porta específica.
     * @throws IOException Se houver falha ao abrir o ServerSocket ou durante a aceitação de conexões.
     */
    public static void main(String[] args) throws IOException {
        int requestedPort = 0;

        if (args.length > 0) {
            requestedPort = Integer.parseInt(args[0]);
        }

        try (var serverSocket = new ServerSocket(requestedPort)) {
            int actualPort = serverSocket.getLocalPort();
            logger.info("Shortener Service subindo na porta automática: " + actualPort);

            // Inicia a rotina contínua de registro (Heartbeat)
            startHeartbeatTask(actualPort);

            while (true) {
                Socket client = serverSocket.accept();
                // Delega o processamento da requisição HTTP para uma nova Virtual Thread
                Thread.ofVirtual().start(() -> handleRequest(client));
            }
        }
    }

    /**
     * Mantém o serviço registrado no Name Registry continuamente.
     * <p>
     * Roda em uma Virtual Thread paralela. Se o nó for removido por falha
     * no Registry ou por instabilidade de rede, ele será reinserido no próximo ciclo de 30 segundos.
     *
     * @param port A porta local efêmera atribuída a esta instância pelo SO.
     */
    private static void startHeartbeatTask(int port) {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    registerInNameRegistry(port);
                    // Espera 30 segundos antes de confirmar a presença novamente
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
     * Intercepta e processa uma requisição HTTP entrante diretamente do Socket.
     * <p>
     * Extrai a linha de comando HTTP principal (Método e Path) e realiza o roteamento
     * para o método processador correspondente da API.
     *
     * @param client O socket conectado representando a requisição do cliente.
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
     * Lida com requisições POST para encurtar uma URL.
     * <p>
     * Realiza o parsing manual do cabeçalho {@code Content-Length} para determinar o tamanho do payload
     * e lê o corpo em JSON. Em seguida, delega a regra de criação e persistência para a
     * Camada de Serviço ({@link UrlService}).
     *
     * @param in  Leitor do fluxo de entrada do socket para extração de cabeçalhos e payload.
     * @param out Fluxo de saída para envio da resposta HTTP (JSON).
     * @throws IOException Caso ocorra erro de I/O durante a leitura/escrita do Socket.
     */
    private static void handleShorten(BufferedReader in, OutputStream out) throws IOException {
        int contentLength = 0;
        String line;
        while (!(line = in.readLine()).isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }

        char[] bodyChars = new char[contentLength];
        in.read(bodyChars);
        String body = new String(bodyChars);

        String originalUrl = HttpProtocol.unmarshalJsonField(body, "url");
        if (originalUrl == null) {
            out.write(HttpProtocol.createResponse(400, "Bad Request", "{\"error\":\"Missing url\"}").getBytes());
            return;
        }

        try {
            // Delega toda a complexidade para a Camada de Serviço
            String code = urlService.shortenUrl(originalUrl);

            String jsonResponse = String.format("{\"newUrl\":\"%s%s\"}", DOMAIN, code);
            out.write(HttpProtocol.createResponse(201, "Created", jsonResponse).getBytes());
            logger.info("URL Encurtada salva no DB: " + originalUrl + " -> " + code);

        } catch (Exception e) {
            logger.severe("Erro interno do servidor: " + e.getMessage());
            out.write(HttpProtocol.createResponse(500, "Internal Server Error", "{\"error\":\"Database Error\"}").getBytes());
        }
    }

    /**
     * Lida com requisições GET para realizar o lookup e redirecionamento de links encurtados.
     * <p>
     * Consulta a Camada de Serviço para buscar a URL original associada ao código curto fornecido.
     * Se encontrada, retorna um status HTTP 302 informando o navegador para realizar o redirecionamento.
     *
     * @param code O código curto em Base62 (ex: "4k9Z") extraído da URI da requisição.
     * @param out  Fluxo de saída para envio dos cabeçalhos HTTP de redirecionamento.
     * @throws IOException Caso ocorra erro de I/O durante a escrita no Socket.
     */
    private static void handleRedirect(String code, OutputStream out) throws IOException {
        try {
            // Delega a busca para a Camada de Serviço
            Optional<String> urlOpt = urlService.getOriginalUrl(code);

            if (urlOpt.isEmpty()) {
                out.write(HttpProtocol.createResponse(404, "Not Found", "{\"error\":\"Code not found\"}").getBytes());
                return;
            }

            String originalUrl = urlOpt.get();
            logger.info("Redirecionando " + code + " para " + originalUrl);
            String response = HttpProtocol.createResponse(302, "Found", "", "Location: " + originalUrl);
            out.write(response.getBytes());

        } catch (Exception e) {
            logger.severe("Erro interno do servidor: " + e.getMessage());
            out.write(HttpProtocol.createResponse(500, "Internal Server Error", "{\"error\":\"Database Error\"}").getBytes());
        }
    }

    /**
     * Dispara o comando de registro para o serviço central de descoberta (Name Registry).
     * <p>
     * Abre uma conexão de Socket simples com o Registry para informar que este nó
     * está online e pronto para receber requisições do Load Balancer.
     *
     * @param port A porta local efêmera atribuída a esta instância pelo SO.
     */
    private static void registerInNameRegistry(int port) {
        try (var socket = new Socket(Config.getString("REGISTRY_HOST", "localhost"),
                Config.getInt("REGISTRY_PORT", 9000));
             var out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("REGISTER app-service localhost:" + port);
            // Reduzido para 'fine' para não poluir o terminal a cada 30 segundos
            logger.fine("Registro renovado no Name Registry (Porta " + port + ")");
        } catch (Exception e) {
            logger.warning("Não foi possível registrar no Name Registry. Ele está online?");
        }
    }
}