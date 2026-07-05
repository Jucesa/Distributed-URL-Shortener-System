package br.upe.nameregistry;

import br.upe.common.Config;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/**
 * Sistema de Registro de Nomes (Service Registry) implementado em Java 21.
 * Permite que serviços (Relays) registrem seus endereços e que o Load Balancer
 * consulte os nós disponíveis para cada serviço.
 *
 * <p>Utiliza Virtual Threads para alta escalabilidade e logs estruturados
 * para monitoramento de registros e consultas.</p>
 *
 * @version 1.3
 */
public class NameRegistryApplication {

    private static final Logger logger = Logger.getLogger(NameRegistryApplication.class.getName());

    /** Porta onde o Registry escutará registros e consultas, definida via Config/Env. */
    private static final int PORT = Config.getInt("REGISTRY_PORT", 9000);

    /** Mapa concorrente que associa o nome do serviço a uma lista de endereços (IP:Porta). */
    private static final Map<String, List<String>> services = new ConcurrentHashMap<>();

    /** Gerador de IDs únicos para rastreamento de transações no log. */
    private static final AtomicInteger transactionIdGenerator = new AtomicInteger(0);

    static {
        // Configura o formato do log para uma linha única e limpa: [Data Hora] [Nível] Mensagem
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$s] %5$s%n");
    }

    /**
     * Ponto de entrada principal. Inicia o servidor de socket do Registry.
     *
     * @param args Argumentos de linha de comando (não utilizados).
     * @throws IOException Se houver erro ao abrir o ServerSocket na porta configurada.
     */
    public static void main(String[] args) throws IOException {
        String ipAddress = "Unknown";
        try {
            ipAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.warning("Não foi possível determinar o endereço IP da rede local.");
        }

        try (var serverSocket = new ServerSocket(PORT)) {
            logger.info(String.format("Name Registry iniciado no IP %s e porta %d", ipAddress, PORT));

            while (true) {
                var clientSocket = serverSocket.accept();
                int tid = transactionIdGenerator.incrementAndGet();

                // Java 21: Virtual Threads para lidar com cada registro/consulta simultaneamente
                Thread.ofVirtual().start(() -> handleTransaction(clientSocket, tid));
            }
        }
    }

    /**
     * Processa uma transação de rede, que pode ser um registro de novo nó,
     * remoção (unregister) ou uma consulta (discovery) de serviço.
     *
     * @param socket Socket da conexão do cliente (Backend ou Load Balancer).
     * @param tid    ID único da transação para rastreabilidade nos logs.
     */
    private static void handleTransaction(Socket socket, int tid) {
        try (socket;
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            String line = in.readLine();
            if (line == null) return;

            String[] parts = line.split(" ");
            String command = parts[0].toUpperCase();

            switch (command) {
                case "REGISTER" -> handleRegister(parts, socket, tid); // Passando o socket aqui
                case "UNREGISTER" -> handleUnregister(parts, tid);
                case "GET" -> handleDiscovery(parts, socket, tid);
                default -> logger.warning(String.format("[TID-%d] Comando desconhecido: %s", tid, command));
            }

        } catch (IOException e) {
            logger.severe(String.format("[TID-%d] Erro na transação: %s", tid, e.getMessage()));
        }
    }

    /**
     * Adiciona um novo endereço de nó à lista de um serviço de forma idempotente.
     * Captura o IP diretamente da conexão do socket.
     *
     * @param parts  Partes da mensagem (REGISTER nome-servico porta).
     * @param socket Socket para extração do IP real do cliente.
     * @param tid    ID da transação para rastreabilidade.
     */
    private static void handleRegister(String[] parts, Socket socket, int tid) {
        // Agora esperamos: REGISTER <nome-servico> <porta>
        if (parts.length < 3) return;

        String serviceName = parts[1];
        String servicePort = parts[2];

        // Extrai o IP real diretamente da conexão TCP
        String clientIp = socket.getInetAddress().getHostAddress();

        // Monta o endereço final que será salvo no Registry
        String address = clientIp + ":" + servicePort;

        // Obtém a lista existente ou cria uma nova se não houver
        CopyOnWriteArrayList<String> nodes = (CopyOnWriteArrayList<String>) services.computeIfAbsent(
                serviceName,
                k -> new CopyOnWriteArrayList<>()
        );

        // Adiciona apenas se o endereço ainda não estiver na lista
        if (nodes.addIfAbsent(address)) {
            logger.info(String.format("[TID-%d] Novo registro: %s -> %s", tid, serviceName, address));
        } else {
            logger.fine(String.format("[TID-%d] Heartbeat recebido (nó já ativo): %s", tid, address));
        }
    }

    /**
     * Remove um endereço de nó da lista de um serviço.
     *
     * @param parts Partes da mensagem (UNREGISTER nome-servico endereco).
     * @param tid   ID da transação.
     */
    private static void handleUnregister(String[] parts, int tid) {
        if (parts.length < 3) return;
        String serviceName = parts[1];
        String address = parts[2]; // No UNREGISTER, o cliente ainda precisa enviar o "IP:Porta" completo para remoção

        List<String> nodes = services.get(serviceName);
        if (nodes != null) {
            nodes.remove(address);
            logger.info(String.format("[TID-%d] Remoção: %s -> %s", tid, serviceName, address));
        }
    }

    /**
     * Responde com a lista de nós disponíveis para o serviço solicitado.
     *
     * @param parts  Partes da mensagem (GET nome-servico).
     * @param socket Socket para envio da resposta.
     * @param tid    ID da transação.
     * @throws IOException Se houver erro ao escrever no socket.
     */
    private static void handleDiscovery(String[] parts, Socket socket, int tid) throws IOException {
        if (parts.length < 2) return;
        String serviceName = parts[1];

        List<String> nodes = services.getOrDefault(serviceName, Collections.emptyList());
        String response = String.join(",", nodes);

        var out = new PrintWriter(socket.getOutputStream(), true);
        out.println(response);

        logger.info(String.format("[TID-%d] Consulta: %s (Nós encontrados: %d)",
                tid, serviceName, nodes.size()));
    }
}