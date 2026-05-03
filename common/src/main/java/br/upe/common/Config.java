package br.upe.common;
import io.github.cdimascio.dotenv.Dotenv;

public class Config {
    // Carrega o .env localizado na raiz do projeto
    private static final Dotenv dotenv = Dotenv.load();

    public static String getString(String key, String defaultValue) {
        String value = dotenv.get(key);
        return (value != null) ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String value = dotenv.get(key);
        return (value != null) ? Integer.parseInt(value) : defaultValue;
    }
}