package br.upe.urlshortener;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser minimalista para requisições e respostas HTTP sobre Sockets.
 */
public class HttpProtocol {

    /**
     * Extrai o valor de um campo JSON simples de uma string.
     * Ex: {"url": "http://teste.com"} -> http://teste.com
     */
    public static String unmarshalJsonField(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();

        int quoteStart = json.indexOf("\"", start);
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Gera uma resposta HTTP completa.
     */
    public static String createResponse(int code, String status, String body, String... headers) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("HTTP/1.1 %d %s\r\n", code, status));
        sb.append("Content-Type: application/json\r\n");
        sb.append("Content-Length: ").append(body.length()).append("\r\n");
        for (String h : headers) sb.append(h).append("\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }
}