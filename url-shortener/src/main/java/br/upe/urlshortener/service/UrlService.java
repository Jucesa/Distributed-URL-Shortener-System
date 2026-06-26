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

    public String shortenUrl(String originalUrl) {
        long nextId = repository.getNextId();
        String shortcode = Base62.encode(nextId);
        Url newUrl = new Url(shortcode, originalUrl);
        repository.save(newUrl);
        return shortcode;
    }

    public Optional<String> getOriginalUrl(String shortcode) {
        return repository.findByShortcode(shortcode).map(Url::getLongUrl);
    }

    // NOVO
    public void registerAccessAsync(String shortcode) {
        Thread.ofVirtual().start(() -> repository.incrementAccessCount(shortcode));
    }

    // NOVO
    public long getAccessCount(String shortcode) {
        return repository.getAccessCount(shortcode);
    }
}