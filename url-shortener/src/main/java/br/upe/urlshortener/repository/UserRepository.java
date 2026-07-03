package br.upe.urlshortener.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.logging.Logger;

public class UserRepository {

    private static final Logger logger = Logger.getLogger(UserRepository.class.getName());
    private static final EntityManagerFactory emf;

    static {
        emf = Persistence.createEntityManagerFactory("url-shortener-pu");
    }

    public boolean isValidApiKey(String apiKey) {
        try (EntityManager em = emf.createEntityManager()) {
            Number count = (Number) em.createNativeQuery(
                    "SELECT COUNT(1) FROM usuarios WHERE api_key = :apiKey")
                    .setParameter("apiKey", apiKey)
                    .getSingleResult();
            return count != null && count.intValue() > 0;
        } catch (Exception e) {
            logger.warning("Falha ao validar API key: " + e.getMessage());
            return false;
        }
    }
}
