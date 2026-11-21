package org.financelab.budget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

/** Configures the two independent sign-ins used by the private-network budget fixture. */
@Configuration
@Profile("fixture")
class DemoOwnerSecurityConfiguration {
    @Bean
    UserDetailsService testOwners(@Value("${budget.owner-a-password}") String first,
                                  @Value("${budget.owner-b-password}") String second) {
        if (!StringUtils.hasText(first) || !StringUtils.hasText(second) || first.equals(second)) {
            throw new IllegalArgumentException("Set two distinct, nonempty test owner passwords.");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("owner-a").password(encoder.encode(first)).roles("OWNER").build(),
                User.withUsername("owner-b").password(encoder.encode(second)).roles("OWNER").build());
    }
}
