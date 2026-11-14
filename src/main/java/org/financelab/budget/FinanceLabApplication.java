package org.financelab.budget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the server that owns the shared SQLite budget. */
@SpringBootApplication
public class FinanceLabApplication {
    /** Creates the root configuration used by Spring Boot. */
    public FinanceLabApplication() {}

    /**
     * Starts the budget web application.
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(FinanceLabApplication.class, args);
    }
}
