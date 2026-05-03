package br.upe.loadbalancer;

import br.upe.common.Config;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/**
 * Representa um balanceador de carga Layer 4 (TCP) implementado em Java 21.
 * Utiliza Virtual Threads para gerenciar conexões de forma escalável e
 * integra-se a um Name Registry para descoberta dinâmica de serviços.
 *
 * <p>Esta versão inclui um sistema de logging enxuto para rastreamento
 * de requisições passo a passo.</p>
 *
 * @version 1.3
 */
public class LoadBalancerApplication {

    private static final Logger logger = Logger.getLogger(LoadBalancerApplication.class.getName());

    /** Endereço do Name Registry obtido via variável de ambiente. */
    private static final String REGISTRY_ADDR = Config.getString("REGISTRY_HOST", "localhost");

    /** Porta do Name Registry obtida via variável de ambiente. */
    private static final int REGISTRY_PORT = Config.getInt("REGISTRY_PORT", 9000);

    /** Porta onde o Load Balancer aceitará requisições. */
    private static final int LB_PORT = Config.getInt("LB_PORT", 8080);

    /** Contador atômico para implementação do algoritmo Round Robin. */
    private static final AtomicInteger counter = new AtomicInteger(0);

    /** Gerador de IDs únicos para rastreamento de requisições nos logs. */
    private static final AtomicInteger requestIdGenerator = new AtomicInteger(0);

    static {
        // Configura o formato do log para uma linha única e limpa: [Data Hora] [Nível] Mensagem
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    /**
     * Ponto de entrada principal. Inicia o servidor de socket e aguarda conexões.
     *
     * @param args Argumentos de linha de comando (não utilizados).
     * @throws IOException Se houver erro ao abrir o ServerSocket.
     */
    public static void main(String[] args) throws IOException {
        try (var serverSocket = new ServerSocket(LB_PORT)) {
            logger.info(String.format("Servidor iniciado na porta %d | Registry: %s:%d",
                    LB_PORT, REGISTRY_ADDR, REGISTRY_PORT));

            while (true) {
                var clientSocket = serverSocket.accept();
                int requestId = requestIdGenerator.incrementAndGet();

                // Java 21: Cada requisição é processada em uma Virtual Thread isolada
                Thread.ofVirtual().start(() -> proxyRequest(clientSocket, requestId));
            }
        }
    }

    /**
     * Processa uma requisição de cliente, realizando a descoberta do serviço
     * e estabelecendo o túnel bidirecional com o backend.
     *
     * @param clientSocket O socket da conexão do cliente original.
     * @param rid          ID único da requisição para rastreabilidade nos logs.
     */
    private static void proxyRequest(Socket clientSocket, int rid) {
        String remoteAddr = clientSocket.getRemoteSocketAddress().toString();
        logger.info(String.format("[RID-%d] Conexão aberta: %s", rid, remoteAddr));

        try {
            // Etapa 1: Service Discovery
            logger.info(String.format("[RID-%d] Buscando nó para 'app-service'...", rid));
            String targetAddr = discoverService("app-service");

            if (targetAddr == null) {
                logger.warning(String.format("[RID-%d] Erro: Nenhum serviço disponível no Registry.", rid));
                sendHttpError(clientSocket, 503, "Service Unavailable");
                return;
            }

            logger.info(String.format("[RID-%d] Roteando fluxo para %s", rid, targetAddr));
            String[] parts = targetAddr.split(":");

            // Etapa 2: Estabelecimento do Túnel com o Backend
            try (var backendSocket = new Socket()) {
                backendSocket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);

                // Tunelamento bidirecional usando Virtual Threads (Pipes)
                var clientToBackend = Thread.ofVirtual().start(() -> transfer(clientSocket, backendSocket));
                var backendToClient = Thread.ofVirtual().start(() -> transfer(backendSocket, clientSocket));

                // Aguarda o encerramento da transmissão de ambos os lados
                clientToBackend.join();
                backendToClient.join();

                logger.info(String.format("[RID-%d] Fluxo finalizado com sucesso.", rid));
            }
        } catch (Exception e) {
            logger.severe(String.format("[RID-%d] Falha crítica: %s", rid, e.getMessage()));
        } finally {
            closeQuietly(clientSocket);
            logger.info(String.format("[RID-%d] Conexão encerrada.", rid));
        }
    }

    /**
     * Consulta o Name Registry para obter a lista de nós disponíveis para um serviço.
     *
     * @param name Nome do serviço a ser descoberto.
     * @return O endereço "host:port" selecionado ou null se nenhum estiver disponível.
     */
    private static String discoverService(String name) {
        try (var socket = new Socket(REGISTRY_ADDR, REGISTRY_PORT);
             var out = new PrintWriter(socket.getOutputStream(), true);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("GET " + name);
            String response = in.readLine();

            if (response == null || response.isBlank()) return null;

            String[] nodes = response.split(",");
            return nodes[Math.abs(counter.getAndIncrement()) % nodes.length];
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transfere dados de um socket para outro.
     * Utiliza {@link InputStream#transferTo(OutputStream)} para máxima eficiência.
     *
     * @param in  Socket de origem.
     * @param out Socket de destino.
     */
    private static void transfer(Socket in, Socket out) {
        try (var input = in.getInputStream(); var output = out.getOutputStream()) {
            input.transferTo(output);
        } catch (IOException e) {
            // Log de depuração opcional para encerramento de stream
        }
    }

    /**
     * Envia uma resposta de erro formatada em HTTP/1.1 via socket puro.
     *
     * @param s    O socket do cliente.
     * @param code Código de status HTTP.
     * @param msg  Mensagem de status.
     * @throws IOException Se houver erro na escrita.
     */
    private static void sendHttpError(Socket s, int code, String msg) throws IOException {
        String resp = String.format("HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n", code, msg);
        s.getOutputStream().write(resp.getBytes());
    }

    /**
     * Fecha um socket sem lançar exceções.
     *
     * @param s Socket a ser fechado.
     */
    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}