package com.opay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Sends outgoing SMS via the Android Wi-Fi SMS Gateway running on the local network.
 *
 * The Android app (e.g., SMS Gateway for Android – open source) exposes a REST API:
 *   POST http://192.168.1.50:8088/send
 *   Body: { "phone": "+91XXXXXXXXXX", "message": "OPAY-RSP: ..." }
 *
 * Configure the gateway IP and port in application.properties:
 *   opay.gateway.android.host=192.168.1.50
 *   opay.gateway.android.port=8088
 */
@Slf4j
@Service
public class AndroidGatewayService {

    private final WebClient webClient;

    @Value("${opay.gateway.android.send-url}")
    private String sendUrl;

    public AndroidGatewayService(
            @Value("${opay.gateway.android.host}") String host,
            @Value("${opay.gateway.android.port}") int port
    ) {
        this.webClient = WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader("Content-Type", "application/json")
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

        Map<String, String> body = Map.of(
                "phone", toPhone,
                "message", truncated
        );

        try {
            webClient.post()
                    .uri("/send")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            log.info("[OPay Gateway] SMS sent to {}", toPhone);
        } catch (Exception e) {
            // Log but don't rethrow – transaction is already committed
            log.error("[OPay Gateway] Failed to send SMS to {}: {}", toPhone, e.getMessage());
        }
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
