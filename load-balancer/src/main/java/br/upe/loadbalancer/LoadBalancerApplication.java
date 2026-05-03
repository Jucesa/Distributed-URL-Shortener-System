package br.upe.loadbalancer;

/**
 * Classe principal responsável por inicializar o serviço de Balanceamento de Carga (Load Balancer).
 * <p>
 * Este componente atua como o ponto de entrada para os clientes, sendo responsável 
 * por receber as requisições e distribuí-las entre as instâncias disponíveis da API 
 * de encurtamento, garantindo a alta disponibilidade do sistema distribuído.
 * </p>
 *
 * @version 1.0
 */
public class LoadBalancerApplication {

    /**
     * Procedimento de inicialização (Entry Point) da aplicação do Load Balancer.
     *
     * @param args Argumentos de linha de comando fornecidos durante a execução do JAR.
     */
    public static void main(String[] args) {
        System.out.println("Hello from load balancer!");
    }
}