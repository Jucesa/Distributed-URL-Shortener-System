package br.upe.urlshortener.repository;

import br.upe.common.Config;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class UserRepository {

    private static final Logger logger = Logger.getLogger(UserRepository.class.getName());
    private static final EntityManagerFactory emf;

    static {

        // Mapeia as variáveis de ambiente para as propriedades do JPA
        Map<String, String> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.url", Config.getString("DB_URL","localhost:5432/url-shortener"));
        properties.put("jakarta.persistence.jdbc.user", Config.getString("DB_USER","postgres"));
        properties.put("jakarta.persistence.jdbc.password", Config.getString("DB_PASSWORD", "admin"));

        // Inicializa o EntityManagerFactory passando as propriedades dinâmicas
        emf = Persistence.createEntityManagerFactory("url-shortener-pu", properties);
    }


    /**
     * NEW: Fetches the User ID (assuming column 'id') using the API Key.
     */
    public Integer getUserIdByApiKey(String apiKey) {
        try (EntityManager em = emf.createEntityManager()) {
            Number result = (Number) em.createNativeQuery(
                            "SELECT id FROM usuarios WHERE api_key = :apiKey")
                    .setParameter("apiKey", apiKey)
                    .getSingleResult();

            return result != null ? result.intValue() : null; // FIXED HERE
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            logger.warning("Falha ao buscar usuário pela API key: " + e.getMessage());
            return null;
        }
    }

    /**
     * NEW: Saves a new user to the database.
     */
    public void saveUser(String username, String apiKey) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            // Using Native Query to match your 'usuarios' table structure.
            // If you have a mapped 'Usuario' entity, you could simply do: em.persist(new Usuario(username, apiKey));
            em.createNativeQuery(
                            "INSERT INTO usuarios (username, api_key) VALUES (?1, ?2)"
                    )
                    .setParameter(1, username)
                    .setParameter(2, apiKey)
                    .executeUpdate();

            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            logger.severe("Erro ao salvar usuário no banco: " + e.getMessage());
            throw new RuntimeException("Database error during user registration", e);
        } finally {
            em.close();
        }
    }
}