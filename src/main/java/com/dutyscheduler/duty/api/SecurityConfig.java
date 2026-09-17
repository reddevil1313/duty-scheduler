package com.dutyscheduler.duty.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Authentication, and the coarse half of authorization.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/session/login", "/actuator/health").permitAll()
                        .anyRequest().authenticated())

                // On, not off. The session lives in a cookie, so a form on another
                // site could otherwise make the browser POST here with that cookie
                // attached. withHttpOnlyFalse lets the React client in step 9 read
                // the token and echo it back in a header, which is the standard SPA
                // arrangement — the cookie is readable, but only by this origin.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))

                .formLogin(form -> form.loginProcessingUrl("/api/session/login"))
                .logout(logout -> logout.logoutUrl("/api/session/logout").deleteCookies("JSESSIONID"))
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * Cost 12. The default is 10; each step doubles the work, so 12 is roughly
     * four times slower to verify and four times slower to attack. On a login
     * that happens once a shift, a couple of hundred milliseconds is free.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
