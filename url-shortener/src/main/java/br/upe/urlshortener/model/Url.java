package br.upe.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Entidade que representa a tabela 'url' no banco de dados.
 */
@Entity
@Table(name = "url")
public class Url {

    @Id
    @Column(name = "shortcode", nullable = false, columnDefinition = "TEXT")
    private String shortcode;

    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Construtor vazio obrigatório pela especificação do Jakarta/JPA.
     */
    public Url() {
    }

    /**
     * Construtor utilitário para facilitar a criação do objeto.
     */
    public Url(String shortcode, String longUrl) {
        this.shortcode = shortcode;
        this.longUrl = longUrl;
    }

    /**
     * Método de callback do Jakarta Persistence.
     * É executado automaticamente imediatamente antes da entidade ser salva no banco (INSERT).
     * Garante que a data de criação seja gerada sem depender do relógio do banco de dados.
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // --- Getters e Setters ---

    public String getShortcode() {
        return shortcode;
    }

    public void setShortcode(String shortcode) {
        this.shortcode = shortcode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}