package br.upe.loadbalancer;

import br.upe.common.Config;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/**
 * Aplicação de Balanceamento de Carga de Camada 4 (TCP) utilizando Java 21.
 *
 * <p>Esta implementação foca em alta escalabilidade através do uso de Virtual Threads,
 * permitindo o gerenciamento de milhares de conexões simultâneas com I/O bloqueante
 * de forma eficiente.</p>
 *
 * <p>As principais estratégias distribuídas implementadas são:</p>
 * <ul>
 *     <li><b>Retry Passivo:</b> Caso um nó falhe na conexão inicial, o balanceador tenta
 *     automaticamente o próximo nó disponível.</li>
 *     <li><b>Health Check Reativo:</b> Identifica falhas de conexão em tempo real e
 *     notifica o Name Registry para remover o nó problemático.</li>
 *     <li><b>Transparência de Localização:</b> O cliente interage apenas com o balanceador,
 *     desconhecendo a topologia interna dos serviços de backend.</li>
 * </ul>
 *
 * @author Italan Leal
 * @version 1.4
 */
public class LoadBalancerApplication {

    private static final Logger logger = Logger.getLogger(LoadBalancerApplication.class.getName());

    /** Endereço do Name Registry para descoberta de serviços. */
    private static final String REGISTRY_ADDR = Config.getString("REGISTRY_HOST", "localhost");

    /** Porta de comunicação com o Name Registry. */
    private static final int REGISTRY_PORT = Config.getInt("REGISTRY_PORT", 9000);

    /** Porta local onde o Load Balancer aceita conexões de entrada. */
    private static final int LB_PORT = Config.getInt("LB_PORT", 8080);

    /** Número máximo de tentativas de reencaminhamento antes de retornar erro ao cliente. */
    private static final int MAX_RETRIES = 3;

    /** Contador atômico utilizado para a distribuição Round Robin dos serviços. */
    private static final AtomicInteger counter = new AtomicInteger(0);

    /** Gerador de identificadores únicos para rastreabilidade de requisições nos logs. */
    private static final AtomicInteger requestIdGenerator = new AtomicInteger(0);

    static {
        // Configuração do formato de log minimalista para facilitar a leitura no console
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    /**
     * Inicializa o servidor de socket e entra no loop de aceitação de clientes.
     *
     * @param args Argumentos de linha de comando (não utilizados).
     * @throws IOException Caso ocorra uma falha crítica ao abrir o ServerSocket.
     */
    public static void main(String[] args) throws IOException {
        try (var serverSocket = new ServerSocket(LB_PORT)) {
            logger.info(String.format("Load Balancer iniciado na porta %d", LB_PORT));

            while (true) {
                var clientSocket = serverSocket.accept();
                int rid = requestIdGenerator.incrementAndGet();

                // Java 21: Criação de uma Virtual Thread por requisição para máximo throughput
                Thread.ofVirtual().start(() -> proxyRequest(clientSocket, rid));
            }
        }
    }

    /**
     * Gerencia o ciclo de vida completo de uma requisição de proxy.
     * <p>Realiza a descoberta do serviço, tenta estabelecer a conexão com o backend
     * e aplica a lógica de retry caso o nó selecionado falhe.</p>
     *
     * @param clientSocket O socket da conexão proveniente do cliente.
     * @param rid          O identificador único da requisição para auditoria.
     */
    private static void proxyRequest(Socket clientSocket, int rid) {
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();
        logger.info(String.format("[RID-%d] Requisição iniciada para: %s", rid, clientInfo));
        try{
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                String targetAddr = discoverService("app-service");

                if (targetAddr == null) {
                    logger.warning(String.format("[RID-%d] Falha: Nenhum nó disponível.", rid));
                    sendErrorResponse(clientSocket, 503, "Service Unavailable");
                    return;
                }

                try (var backendSocket = new Socket()) {
                    // Timeout de 2s para garantir que o balanceador não fique preso em nós zumbis
                    backendSocket.connect(new InetSocketAddress(
                            targetAddr.split(":")[0],
                            Integer.parseInt(targetAddr.split(":")[1])
                    ), 2000);

                    logger.info(String.format("[RID-%d] [Tentativa %d] Roteando para: %s", rid, attempt, targetAddr));

                    executeTunneling(clientSocket, backendSocket);
                    return; // Fluxo finalizado com sucesso

                } catch (IOException e) {
                    // Lógica Reativa: Detecta falha e limpa o Registry imediatamente
                    logger.severe(String.format("[RID-%d] Nó %s inacessível. Removendo do Registry...", rid, targetAddr));
                    unregisterFailedNode(targetAddr);

                    if (attempt == MAX_RETRIES) {
                        logger.severe(String.format("[RID-%d] Máximo de tentativas atingido.", rid));
                        sendErrorResponse(clientSocket, 504, "Gateway Timeout");
                    }
                }
            }
        } finally {
            closeQuietly(clientSocket);

        }
    }

    /**
     * Estabelece o túnel bidirecional de dados entre o cliente e o backend.
     * <p>Utiliza duas Virtual Threads para garantir que o fluxo de leitura e escrita
     * ocorra de forma independente (Full Duplex).</p>
     *
     * @param client  Socket do cliente.
     * @param backend Socket do servidor de backend (relay).
     */
    private static void executeTunneling(Socket client, Socket backend) {
        try {
            var c2b = Thread.ofVirtual().start(() -> transfer(client, backend));
            var b2c = Thread.ofVirtual().start(() -> transfer(backend, client));

            c2b.join();
            b2c.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Tunelamento interrompido.");
        }
    }

    /**
     * Consulta o Name Registry para obter um nó disponível para o serviço solicitado.
     * <p>A seleção do nó é feita através do algoritmo Round Robin baseado em um
     * contador atômico.</p>
     *
     * @param name Nome do serviço registrado (ex: "app-service").
     * @return O endereço "host:porta" do nó selecionado ou {@code null} se indisponível.
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
        } catch (IOException e) {
            logger.severe("Erro ao consultar Name Registry: " + e.getMessage());
            return null;
        }
    }

    /**
     * Notifica o Name Registry para remover um nó que apresentou falha técnica.
     *
     * @param address O endereço "host:porta" do nó a ser removido.
     */
    private static void unregisterFailedNode(String address) {
        try (var socket = new Socket(REGISTRY_ADDR, REGISTRY_PORT);
             var out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("UNREGISTER app-service " + address);
        } catch (IOException e) {
            logger.warning("Falha ao comunicar remoção ao Registry: " + e.getMessage());
        }
    }

    /**
     * Realiza a transferência bruta de bytes entre dois streams.
     *
     * @param in  Socket de entrada.
     * @param out Socket de saída.
     */
    private static void transfer(Socket in, Socket out) {
        try (var input = in.getInputStream(); var output = out.getOutputStream()) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // Conexão encerrada por uma das partes
        }
    }

    /**
     * Envia uma resposta HTTP de erro simplificada para o cliente via socket puro.
     *
     * @param s    Socket do cliente.
     * @param code Código de status HTTP (ex: 503, 504).
     * @param msg  Mensagem descritiva do erro.
     */
    private static void sendErrorResponse(Socket s, int code, String msg) {
        try {
            String resp = String.format("HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n%s",
                    code, msg, msg);
            OutputStream os = s.getOutputStream();
            os.write(resp.getBytes());
            os.flush(); // Garante que os bytes saíram do buffer da JVM para a rede
            s.close();  // Avisa ao cliente (Postman) que a conversa acabou
        } catch (IOException ignored) {}
    }

    /**
     * Fecha o socket de forma silenciosa, ignorando possíveis exceções de I/O.
     *
     * @param s Socket a ser fechado.
     */
    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}