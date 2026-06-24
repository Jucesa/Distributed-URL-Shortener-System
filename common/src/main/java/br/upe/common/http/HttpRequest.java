package br.upe.common.http;

import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    private String method;
    private String path;
    private Map<String, String> headers = new HashMap<>();
    private String body;

    // Getters e Setters
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Map<String, String> getHeaders() { return headers; }
    public void addHeader(String key, String value) { this.headers.put(key.toLowerCase(), value); }
    public String getHeader(String key) { return headers.get(key.toLowerCase()); }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}