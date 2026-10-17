package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Authentication and URL-level authorization. Method-level checks
 * ({@code @PreAuthorize}) do the rest.
 *
 * <p>
 * Login and logout answer with status codes instead of the default redirects:
 * {@code fetch} follows a 302 silently, so a wrong password would look like a
 * successful sign-in.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** RFC 9457 by hand: no controller handles a failed login. */
    private static final String SIGN_IN_FAILED = """
            {"type":"about:blank","title":"Sign-in failed",\
            "status":401,"detail":"That username and password did not match."}""";

    /** Same origin only. Inline styles are allowed because React sets some. */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private static final String LOGIN = "/api/session/login";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AppUserRepository users, LoginThrottle throttle,
            DutyMetrics metrics) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(LOGIN, "/actuator/health").permitAll()
                        // The client's page and assets. No data in them.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                        // Otherwise anonymous errors (e.g. accepting an invite) turn into 401s.
                        .requestMatchers("/error").permitAll()
                        // Hands out the CSRF token the login POST needs.
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        // Invite links are used before the person has an account.
                        // One segment only, so /api/invites itself stays closed.
                        .requestMatchers(HttpMethod.GET, "/api/invites/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/invites/*/accept").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OWNER")
                        // Roles are checked on the controller methods.
                        .anyRequest().authenticated())

                .addFilterBefore(new AccountCheckFilter(users), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new LoginThrottleFilter(throttle, metrics, LOGIN), UsernamePasswordAuthenticationFilter.class)

                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))

                // Plain 401, no WWW-Authenticate, so the browser never shows its
                // own login box.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                // The client gets the token from GET /api/session, so the cookie
                // can stay HttpOnly.
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))

                .formLogin(form -> form
                        .loginProcessingUrl(LOGIN)
                        // The client re-reads /api/session next (the CSRF token rotates).
                        .successHandler((request, response, authentication) -> response
                                .setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write(SIGN_IN_FAILED);
                        }))

                .logout(logout -> logout
                        .logoutUrl("/api/session/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response
                                .setStatus(HttpStatus.NO_CONTENT.value())))

                // For curl and the Prometheus scraper.
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /** Cost 12: about 4x the default's work. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
