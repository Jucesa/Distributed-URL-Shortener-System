package br.upe.common;
/**
 * Utilitário para conversão de IDs numéricos em strings Base62.
 * Implementa bootstrapping e ofuscação simples.
 */
public class Base62 {
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final long SECRET_MASK = 0x5DEECE66DL; // Máscara para ofuscação

    /**
     * Converte um ID numérico para Base62 com ofuscação.
     * @param id ID sequencial (ex: vindo de um contador)
     * @return String Base62 ofuscada
     */
    public static String encode(long id) {
        // Ofuscação: XOR com chave secreta para evitar previsibilidade
        long obfuscated = id ^ SECRET_MASK;
        StringBuilder sb = new StringBuilder();
        while (obfuscated > 0) {
            sb.append(CHARS.charAt((int) (obfuscated % 62)));
            obfuscated /= 62;
        }
        return sb.reverse().toString();
    }
}