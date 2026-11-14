package org.financelab.budget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class TestOwnerSecurity {
    @Bean
    UserDetailsService testOwners(@Value("${budget.owner-a-password}") String first,
                                  @Value("${budget.owner-b-password}") String second) {
        if (first.isBlank() || second.isBlank() || first.equals(second)) {
            throw new IllegalArgumentException("Set two distinct, nonempty test owner passwords.");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("owner-a").password(encoder.encode(first)).roles("OWNER").build(),
                User.withUsername("owner-b").password(encoder.encode(second)).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain budgetAccess(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(access -> access
                        .requestMatchers("/budget.css", "/month.js", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login")
                        .defaultSuccessUrl(MonthController.MARCH_PATH, true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .build();
    }
}
