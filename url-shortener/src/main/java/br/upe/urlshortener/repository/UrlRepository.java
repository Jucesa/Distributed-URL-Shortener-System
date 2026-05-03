package br.upe.urlshortener.repository;

import br.upe.common.Config;
import br.upe.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Classe responsável por abstrair o acesso ao banco de dados para a entidade {@link Url}.
 * <p>
 * Implementa o padrão Repository (Data Access Object), utilizando a especificação Jakarta JPA
 * (com Hibernate como provedor) para realizar as operações de persistência e consulta.
 * <p>
 * Diferente de uma inicialização JPA padrão, esta classe carrega as credenciais do banco
 * dinamicamente através de variáveis de ambiente (.env), permitindo a conexão segura com
 * bancos em nuvem (como o Neon DB) sem a necessidade de expor senhas no {@code persistence.xml}.
 *
 * @version 1.1
 */
public class UrlRepository {

    private static final Logger logger = Logger.getLogger(UrlRepository.class.getName());

    /**
     * Fábrica de gerenciadores de entidade (EntityManager).
     * Por ser um objeto de inicialização "pesada", é mantido como estático e final
     * para garantir que seja instanciado apenas uma vez por aplicação (Singleton).
     */
    private static final EntityManagerFactory emf;

    /*
     * Bloco de inicialização estática.
     * Executado automaticamente pela JVM no momento em que a classe é carregada.
     * Monta o mapa de propriedades de conexão lendo a classe utilitária Config (.env)
     * e injeta essas propriedades na criação do EntityManagerFactory.
     */
    static {
        Map<String, String> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.url", Config.getString("DB_URL", "jdbc:postgresql://localhost:5432/urlshortener_db"));
        properties.put("jakarta.persistence.jdbc.user", Config.getString("DB_USER", "postgres"));
        properties.put("jakarta.persistence.jdbc.password", Config.getString("DB_PASS", "password"));

        emf = Persistence.createEntityManagerFactory("url-shortener-pu", properties);
    }

    /**
     * Consulta a sequência atômica do PostgreSQL para obter um identificador único.
     * <p>
     * O uso de uma {@code SEQUENCE} no banco de dados previne condições de corrida
     * (race conditions) e conflitos de Primary Key quando múltiplas instâncias da API
     * tentam gerar novas URLs encurtadas simultaneamente.
     *
     * @return O próximo ID numérico único e incremental gerado pelo banco.
     */
    public long getNextId() {
        try (EntityManager em = emf.createEntityManager()) {
            // Executa a função nativa do Postgres para avançar a sequência e retornar o valor
            Number nextVal = (Number) em.createNativeQuery("SELECT nextval('url_id_seq')").getSingleResult();
            return nextVal.longValue();
        }
    }

    /**
     * Persiste uma nova entidade {@link Url} no banco de dados.
     * <p>
     * O método gerencia a transação manualmente. Caso ocorra alguma falha durante
     * o processo de {@code INSERT}, a transação sofre um {@code rollback} para
     * garantir a integridade dos dados e a exceção é propagada para o chamador.
     *
     * @param url A entidade Url preenchida com o código curto e a URL original.
     * @throws RuntimeException Se ocorrer uma falha na transação ou na comunicação com o banco.
     */
    public void save(Url url) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(url);
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            logger.severe("Erro ao salvar URL no JPA: " + e.getMessage());
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * Busca uma URL persistida no banco de dados utilizando o seu código curto (Primary Key).
     * <p>
     * Utiliza o método otimizado {@code EntityManager.find()} que aproveita o cache de
     * primeiro nível do JPA antes de disparar um {@code SELECT} no banco relacional.
     *
     * @param shortcode O código em Base62 (ex: "4k9Z") que representa a Primary Key.
     * @return Um {@link Optional} contendo a entidade Url se encontrada, ou vazio caso contrário.
     */
    public Optional<Url> findByShortcode(String shortcode) {
        try (EntityManager em = emf.createEntityManager()) {
            Url url = em.find(Url.class, shortcode);
            return Optional.ofNullable(url);
        }
    }
}