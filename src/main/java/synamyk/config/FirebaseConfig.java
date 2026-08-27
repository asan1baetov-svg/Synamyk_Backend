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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Initializes Firebase Cloud Messaging for admin push broadcasts.
 *
 * <p>Credentials are taken from, in order:
 * <ol>
 *   <li>{@code FIREBASE_CREDENTIALS_JSON} — the service-account JSON inline
 *       (raw JSON or base64-encoded). Preferred on platforms without a writable FS (Railway).</li>
 *   <li>{@code FIREBASE_CREDENTIALS_PATH} — a resource path (classpath:/file:/plain).</li>
 * </ol>
 *
 * <p>Returns a {@code null} bean (instead of throwing) when disabled or misconfigured,
 * so the app boots fine before real Firebase credentials are supplied.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${firebase.enabled:false}")
    private boolean enabled;

    @Value("${firebase.credentials-path:firebase-service-account.json}")
    private String credentialsPath;

    @Value("${firebase.credentials-json:}")
    private String credentialsJson;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (!enabled) {
            log.warn("Firebase push notifications disabled (firebase.enabled=false)");
            return null;
        }
        try {
            try (InputStream in = openCredentials()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp app = FirebaseApp.getApps().isEmpty()
                        ? FirebaseApp.initializeApp(options)
                        : FirebaseApp.getInstance();
                log.info("Firebase initialized successfully ({})",
                        hasInlineJson() ? "from FIREBASE_CREDENTIALS_JSON" : "from " + credentialsPath);
                return FirebaseMessaging.getInstance(app);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize Firebase (push notifications will be disabled): {}", e.getMessage());
            return null;
        }
    }

    private boolean hasInlineJson() {
        return credentialsJson != null && !credentialsJson.isBlank();
    }

    private InputStream openCredentials() throws Exception {
        if (hasInlineJson()) {
            String raw = credentialsJson.trim();
            // Accept either raw JSON or a base64-encoded blob.
            byte[] bytes = raw.startsWith("{")
                    ? raw.getBytes(StandardCharsets.UTF_8)
                    : Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
            return new ByteArrayInputStream(bytes);
        }
        Resource resource = new DefaultResourceLoader().getResource(credentialsPath);
        return resource.getInputStream();
    }
}
