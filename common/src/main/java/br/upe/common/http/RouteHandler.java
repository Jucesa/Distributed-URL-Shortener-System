package br.upe.common.http;

@FunctionalInterface
public interface RouteHandler {
    void handle(HttpRequest req, HttpResponse res) throws Exception;
}
