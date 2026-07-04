package br.upe.urlshortener.service;

import br.upe.common.Base62;
import br.upe.urlshortener.model.Url;
import br.upe.urlshortener.repository.UrlRepository;

import java.util.Optional;

public class UrlService {

    private final UrlRepository repository;

    public UrlService() {
        this.repository = new UrlRepository();
    }

    // UPDATED: Now requires the userId to establish ownership
    public String shortenUrl(String originalUrl, Integer userId) {
        long nextId = repository.getNextId();
        String shortcode = Base62.encode(nextId);

        // Note: You will need to update the Url model constructor to accept userId!
        Url newUrl = new Url(shortcode, originalUrl, userId);
        repository.save(newUrl);
        return shortcode;
    }

    public Optional<String> getOriginalUrl(String shortcode) {
        return repository.findByShortcode(shortcode).map(Url::getLongUrl);
    }

    public void registerAccessAsync(String shortcode) {
        Thread.ofVirtual().start(() -> repository.incrementAccessCount(shortcode));
    }

    public long getAccessCount(String shortcode) {
        return repository.getAccessCount(shortcode);
    } // FIXED: Added the missing closing brace here

    public boolean deleteUrl(String shortcode) {
        return repository.deleteByShortcode(shortcode);
    }

    // NEW: Validates if the given user owns the URL
    public boolean isOwner(String shortcode, Integer userId) {
        Optional<Url> urlOpt = repository.findByShortcode(shortcode);

        if (urlOpt.isEmpty()) {
            return false;
        }

        // Note: You will need a getUserId() getter in your Url model
        Integer ownerId = urlOpt.get().getUserId();
        return ownerId != null && ownerId.equals(userId);
    }
}