package com.opay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sends outgoing SMS via the Android Wi-Fi SMS Gateway running on the local network.
 *
 * The Android app (SMS Gateway for Android) exposes a REST API:
 *   POST http://192.168.29.7:8080/message
 *   Body: { "textMessage": { "text": "OPAY-RSP: ..." }, "phoneNumbers": ["+91XXXXXXXXXX"] }
 *
 * Configure the gateway IP, port, and basic auth in application.properties.
 */
@Slf4j
@Service
public class AndroidGatewayService {

    private final Queue<Map<String, String>> outgoingQueue = new ConcurrentLinkedQueue<>();
    private final WebClient webClient;

    @Value("${opay.gateway.android.send-url}")
    private String sendUrl;

    public AndroidGatewayService(
            @Value("${opay.gateway.android.host}") String host,
            @Value("${opay.gateway.android.port}") int port,
            @Value("${opay.gateway.android.username}") String username,
            @Value("${opay.gateway.android.password}") String password
    ) {
        this.webClient = WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
    }

    /**
     * Sends an SMS to the specified phone number via the Android gateway.
     * Non-blocking; logs failures but does not throw.
     *
     * @param toPhone   destination phone number (E.164 format, e.g. "+919876543210")
     * @param message   SMS body (≤160 chars, already prefixed with OPAY-RSP:)
     */
    public void sendSMS(String toPhone, String message) {
        // Enforce 160-char limit at the point of sending
        String truncated = message.length() > 160 ? message.substring(0, 160) : message;

        Map<String, Object> body = Map.of(
                "textMessage", Map.of("text", truncated),
                "phoneNumbers", List.of(toPhone)
        );

        try {
            outgoingQueue.add(Map.of("to", toPhone, "message", truncated));
            log.info("[OPay Gateway] Queued SMS for Termux to {}", toPhone);
            
            /* Disabled old Wi-Fi Gateway logic
            webClient.post()
                    .uri("/message")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            log.info("[OPay Gateway] SMS sent via old gateway to {}", toPhone);
            */
        } catch (Exception e) {
            // Log but don't rethrow – transaction is already committed
            log.error("[OPay Gateway] Failed to queue SMS to {}: {}", toPhone, e.getMessage());
        }
    }

    public Map<String, String> popOutgoingSms() {
        return outgoingQueue.poll();
    }

    /**
     * Health check – tests connectivity to the Android gateway.
     */
    public boolean isGatewayReachable() {
        try {
            String result = webClient.get()
                    .uri("/status")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return result != null;
        } catch (Exception e) {
            log.warn("[OPay Gateway] Health check failed: {}", e.getMessage());
            return false;
        }
    }
}
