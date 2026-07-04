package br.upe.urlshortener.service;

import br.upe.common.Base62;
import br.upe.urlshortener.model.Url;
import br.upe.urlshortener.repository.UrlRepository;

import java.util.Optional;

/**
 * Camada de Serviço (Business Logic) para o Encurtador de URLs.
 * <p>
 * Orquestra as operações entre a entrada de dados do usuário e a camada
 * de persistência (Repository), garantindo que as regras de geração de código
 * e formatação sejam aplicadas corretamente.
 */
public class UrlService {

    private final UrlRepository repository;

    /**
     * Construtor que injeta a dependência do repositório.
     */
    public UrlService() {
        this.repository = new UrlRepository();
    }

    /**
     * Encurta uma URL original solicitando um ID único ao banco de dados.
     *
     * @param originalUrl A URL longa fornecida pelo usuário.
     * @return O código curto em Base62 persistido no banco.
     * @throws RuntimeException Se houver erro de persistência.
     */
    public String shortenUrl(String originalUrl) {
        // 1. Pede ao PostgreSQL um ID incremental atômico
        long nextId = repository.getNextId();

        // 2. Codifica o ID numérico em uma string Base62
        String shortcode = Base62.encode(nextId);

        // 3. Persiste a entidade usando o repositório
        Url newUrl = new Url(shortcode, originalUrl);
        repository.save(newUrl);

        return shortcode;
    }

    /**
     * Busca a URL original baseada no código curto fornecido.
     *
     * @param shortcode O código em Base62.
     * @return Um Optional contendo a URL longa, ou vazio se não for encontrada.
     */
    public Optional<String> getOriginalUrl(String shortcode) {
        return repository.findByShortcode(shortcode).map(Url::getLongUrl);
    }

    public boolean deleteUrl(String shortcode) {
        return repository.deleteByShortcode(shortcode);
    }
}