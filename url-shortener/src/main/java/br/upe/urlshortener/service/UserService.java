package br.upe.urlshortener.service;

import br.upe.urlshortener.repository.UserRepository;
import java.util.UUID;

public class UserService {

    private final UserRepository userRepository;

    public UserService() {
        this.userRepository = new UserRepository();
    }

    /**
     * Registers a new user, generates a secure API key, saves it,
     * and returns the key to the controller.
     */
    public String registerUser(String username) {
        // Generate a random, standard UUID to act as the API key
        String apiKey = UUID.randomUUID().toString();

        // Delegate the actual database insertion to the repository
        userRepository.saveUser(username, apiKey);

        return apiKey;
    }

    /**
     * Validates the API key and returns the associated User ID.
     * Returns null if the key is invalid or doesn't exist.
     */
    public Integer authenticateByApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        // Delegate the database lookup to the repository
        return userRepository.getUserIdByApiKey(apiKey);
    }
}