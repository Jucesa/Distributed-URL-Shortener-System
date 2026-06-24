package br.upe.common.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class HttpParser {

    public static HttpRequest parse(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        HttpRequest request = new HttpRequest();

        // 1. Lê a linha de requisição (Ex: POST /api/v1/shorten HTTP/1.1)
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        String[] parts = requestLine.split(" ");
        request.setMethod(parts[0]);
        request.setPath(parts[1]);

        // 2. Lê os cabeçalhos
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            String[] headerParts = headerLine.split(":", 2);
            if (headerParts.length == 2) {
                request.addHeader(headerParts[0].trim(), headerParts[1].trim());
            }
        }

        // 3. Lê o corpo (se houver Content-Length)
        String contentLengthStr = request.getHeader("content-length");
        if (contentLengthStr != null) {
            int contentLength = Integer.parseInt(contentLengthStr);
            char[] bodyChars = new char[contentLength];
            reader.read(bodyChars, 0, contentLength);
            request.setBody(new String(bodyChars));
        }

        return request;
    }
}