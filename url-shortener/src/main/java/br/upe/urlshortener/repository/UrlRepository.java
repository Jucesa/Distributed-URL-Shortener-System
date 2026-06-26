package br.upe.urlshortener.repository;

import br.upe.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.Optional;
import java.util.logging.Logger;

public class UrlRepository {

    private static final Logger logger = Logger.getLogger(UrlRepository.class.getName());
    private static final EntityManagerFactory emf;

    static {
        emf = Persistence.createEntityManagerFactory("url-shortener-pu");
    }

    public long getNextId() {
        try (EntityManager em = emf.createEntityManager()) {
            Number nextVal = (Number) em.createNativeQuery("SELECT nextval('url_id_seq')").getSingleResult();
            return nextVal.longValue();
        }
    }

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

    public Optional<Url> findByShortcode(String shortcode) {
        try (EntityManager em = emf.createEntityManager()) {
            Url url = em.find(Url.class, shortcode);
            return Optional.ofNullable(url);
        }
    }

    // NOVO
    public void incrementAccessCount(String shortcode) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery(
                "UPDATE url SET access_count = access_count + 1 WHERE shortcode = ?1"
            )
            .setParameter(1, shortcode)
            .executeUpdate();
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            logger.severe("Erro ao incrementar access_count: " + e.getMessage());
        } finally {
            em.close();
        }
    }

    // NOVO
    public long getAccessCount(String shortcode) {
        try (EntityManager em = emf.createEntityManager()) {
            Number result = (Number) em.createNativeQuery(
                "SELECT access_count FROM url WHERE shortcode = ?1"
            )
            .setParameter(1, shortcode)
            .getSingleResult();
            return result != null ? result.longValue() : 0;
        }
    }
}