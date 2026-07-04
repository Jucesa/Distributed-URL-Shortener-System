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

    // NEW: Links the URL to the user who created it
// In Url.java
    @Column(name = "user_id")
    private Integer userId; // Ensure this is Integer, not String!

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "access_count")
    private long accessCount = 0;

    // Default constructor required by JPA
    public Url() {}

    // UPDATED: Constructor now requires the userId to establish ownership
    public Url(String shortcode, String longUrl, Integer userId) {
        this.shortcode = shortcode;
        this.longUrl = longUrl;
        this.userId = userId;
        this.accessCount = 0;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public String getShortcode()         { return shortcode; }
    public void setShortcode(String s)   { this.shortcode = s; }

    public String getLongUrl()           { return longUrl; }
    public void setLongUrl(String l)     { this.longUrl = l; }

    // NEW: Getter and Setter for userId
    public Integer getUserId()            { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt()  { return createdAt; }

    public long getAccessCount()         { return accessCount; }
    public void setAccessCount(long c)   { this.accessCount = c; }
}