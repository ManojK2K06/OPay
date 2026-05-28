package com.opay.config;

import com.opay.model.Account;
import com.opay.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.Security;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AppConfig {

    @Bean
    public CommandLineRunner seedDatabase(AccountRepository accountRepository) {
        return args -> {
            // Register Bouncy Castle globally
            Security.insertProviderAt(new BouncyCastleProvider(), 1);

            if (accountRepository.count() == 0) {
                accountRepository.save(new Account(null, "1234567890",
                        100_000_00L, "Alice Kumar", "+911111111111", true, null, null));
                accountRepository.save(new Account(null, "0987654321",
                        50_000_00L, "Bob Sharma", "+912222222222", true, null, null));
                accountRepository.save(new Account(null, "1122334455",
                        200_000_00L, "Carol Patel", "+913333333333", true, null, null));
                log.info("[OPay] Seeded 3 test accounts.");
            }
                log.info("[OPay] Server ready. H2 console: http://localhost:8080/h2-console | DB: ./data/opaydb");
        };
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
