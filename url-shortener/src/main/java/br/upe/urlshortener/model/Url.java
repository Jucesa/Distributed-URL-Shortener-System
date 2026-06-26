package br.upe.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

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

    @Column(name = "access_count")
    private long accessCount = 0;

    public Url() {}

    public Url(String shortcode, String longUrl) {
        this.shortcode = shortcode;
        this.longUrl = longUrl;
        this.accessCount = 0;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public String getShortcode()         { return shortcode; }
    public void setShortcode(String s)   { this.shortcode = s; }
    public String getLongUrl()           { return longUrl; }
    public void setLongUrl(String l)     { this.longUrl = l; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public long getAccessCount()         { return accessCount; }
    public void setAccessCount(long c)   { this.accessCount = c; }
}