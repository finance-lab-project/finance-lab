package org.financelab.budget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class BudgetSecurityConfiguration {
    @Bean
    @Profile("!fixture")
    UserDetailsService pilotOwner(@Value("${budget.pilot-password-hash}") String hash) {
        if (!hash.matches("\\$2[aby]\\$(?:1[0-9]|2[0-9]|3[01])\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Supply a bcrypt pilot password hash with cost 10 or higher.");
        }
        var owner = User.withUsername("pilot-owner").password("{bcrypt}" + hash).roles("OWNER").build();
        return new InMemoryUserDetailsManager(owner);
    }

    @Bean
    SecurityFilterChain budgetAccess(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(access -> access
                        .requestMatchers("/budget.css", "/month.js", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login")
                        .defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .build();
    }
}
