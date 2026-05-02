package br.upe.urlshortener;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1")
// @Tag define o agrupamento e a descrição na página do Swagger
@Tag(name = "Links", description = "Endpoints para encurtamento e redirecionamento de URLs")
public class LinkController {

    // @Operation é o "comentário" principal que aparecerá no Swagger detalhando a função
    @Operation(
        summary = "Gera um link curto",
        description = "Recebe uma URL longa e retorna um código curto e único protegido contra colisões."
    )
    // @ApiResponses mapeia os possíveis códigos HTTP de retorno para a documentação
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Hash único gerado e persistido com sucesso"),
        @ApiResponse(responseCode = "400", description = "URL em formato inválido fornecida")
    })
    @PostMapping("/encurtar")
    public ResponseEntity<String> shortenUrl(
        // @Parameter documenta o payload ou parâmetros recebidos
        @Parameter(description = "A URL original completa (ex: https://site.com/artigo)", required = true)
        @RequestBody String originalUrl) {

        // TODO: Buscar ID no Redis, processar pelo Hashids, salvar no SQL com TTL e retornar.
        String shortCode = "3xZ1a"; 
        return ResponseEntity.status(HttpStatus.CREATED).body("http://meu.link/" + shortCode);
    }

    @Operation(
        summary = "Redireciona para a URL original",
        description = "Recebe o código curto e realiza o redirecionamento (Lookup) via HTTP 301 Permanente ou 302 Temporário."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "301", description = "Redirecionamento permanente realizado"),
        @ApiResponse(responseCode = "404", description = "Código não encontrado ou expirado (TTL)")
    })
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToOriginal(
        @Parameter(description = "Código curto (ex: 3xZ1a)", required = true)
        @PathVariable String shortCode) {

        // TODO: Buscar shortCode no cache (Redis) e depois no DB (SQL).
        String originalUrl = "https://www.exemplo.com/url-muito-longa-original";

        // Realiza o redirecionamento HTTP, atendendo à regra de negócio.
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY) // Retorna 301
                .location(URI.create(originalUrl))
                .build();
    }
}