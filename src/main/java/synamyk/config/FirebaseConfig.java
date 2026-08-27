package synamyk.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Initializes Firebase Cloud Messaging for admin push broadcasts.
 * Returns a null bean (instead of throwing) when disabled or misconfigured,
 * so the app boots fine before real Firebase credentials are supplied.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${firebase.enabled:false}")
    private boolean enabled;

    @Value("${firebase.credentials-path:firebase-service-account.json}")
    private String credentialsPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (!enabled) {
            log.warn("Firebase push notifications disabled (firebase.enabled=false)");
            return null;
        }
        try {
            Resource resource = new DefaultResourceLoader().getResource(credentialsPath);
            try (InputStream in = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp app = FirebaseApp.getApps().isEmpty()
                        ? FirebaseApp.initializeApp(options)
                        : FirebaseApp.getInstance();
                log.info("Firebase initialized successfully from {}", credentialsPath);
                return FirebaseMessaging.getInstance(app);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize Firebase (push notifications will be disabled): {}", e.getMessage());
            return null;
        }
    }
}
