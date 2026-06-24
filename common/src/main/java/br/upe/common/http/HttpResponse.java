package br.upe.common.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private final OutputStream out;
    private int statusCode = 200;
    private final Map<String, String> headers = new HashMap<>();

    public HttpResponse(OutputStream out) {
        this.out = out;
        // Cabeçalho padrão
        this.headers.put("Connection", "close");
    }

    // Retorna 'this' para permitir encadeamento: res.status(404).send(...)
    public HttpResponse status(int code) {
        this.statusCode = code;
        return this;
    }

    public HttpResponse header(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public void json(String jsonBody) throws IOException {
        header("Content-Type", "application/json; charset=UTF-8");
        send(jsonBody);
    }

    public void redirect(String url) throws IOException {
        status(302);
        header("Location", url);
        send("");
    }

    public void send(String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        header("Content-Length", String.valueOf(bodyBytes.length));

        StringBuilder responseBuilder = new StringBuilder();

        // 👇 AJUSTE AQUI: Traduzindo o código para o texto correto
        String statusText = "OK";
        if (statusCode == 302) statusText = "Found";
        if (statusCode == 201) statusText = "Created";
        if (statusCode >= 400) statusText = "Error"; // Genérico para erros

        responseBuilder.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            responseBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }

        responseBuilder.append("\r\n");

        out.write(responseBuilder.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}