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
 * Authentication, and the coarse half of authorization.
 *
 * <p>
 * Written in the lambda DSL, which is not a style choice in Spring Security 7 —
 * the old {@code .and()} chaining was removed, along with
 * {@code authorizeRequests()} in favour of {@code authorizeHttpRequests()}.
 * Every configurer takes its own lambda; chaining still works <em>inside</em>
 * one.
 *
 * <p>
 * Step 9 changed three things here, all for the same reason: a browser client
 * that speaks JSON needs status codes, not redirects. Left at their defaults,
 * {@code formLogin} answers a failed sign-in with a 302 to {@code /login?error}
 * and a successful one with a 302 to {@code /} — which {@code fetch} follows
 * silently, so a wrong password comes back as a 200 with an HTML page in it and
 * the client reports success.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * RFC 9457, written by hand because no controller is involved in a failed
     * login.
     */
    private static final String SIGN_IN_FAILED = """
            {"type":"about:blank","title":"Sign-in failed",\
            "status":401,"detail":"That username and password did not match."}""";

    /**
     * What the page may load and run. Everything from this origin and nothing
     * else; inline style attributes only, because React sets some, and no
     * inline script at all. Framing is refused, so the board cannot be laid
     * under another site's clicks.
     */
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
    public SecurityFilterChain filterChain(HttpSecurity http, AppUserRepository users, LoginThrottle throttle)
            throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(LOGIN, "/actuator/health").permitAll()
                        // Boot's error page, which a failure Spring answers itself
                        // forwards to. Closed, it turned every such failure for a caller
                        // with no account — someone accepting an invite — into a 401.
                        // It shows a status and a path, nothing that needs an account.
                        .requestMatchers("/error").permitAll()
                        // GET only, and named explicitly rather than by prefix. This is
                        // the endpoint that hands out the CSRF token a client needs
                        // before it can post to the login URL, so it has to be reachable
                        // while anonymous — but only for reading.
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        // An invite link is opened, and accepted, by someone with no
                        // account yet. One path segment, so the admin's own list at
                        // /api/invites is not caught by it, and GET only for the
                        // view, so revoking (DELETE on the same shape) still needs an
                        // account. The token is the guard; InviteController says more.
                        .requestMatchers(HttpMethod.GET, "/api/invites/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/invites/*/accept").permitAll()
                        // The rest of the actuator says how the server is doing —
                        // memory, requests, timings — which is the owner's business,
                        // not every team member's. There is no method to annotate here,
                        // so this is the one rule that has to be a URL rule.
                        .requestMatchers("/actuator/**").hasRole("OWNER")
                        // Everything else needs an account. Note what this does NOT do:
                        // it does not distinguish reading from writing. Doing that here
                        // would mean every new endpoint needs a URL rule adding, and the
                        // one you forget is the one that ships open. @PreAuthorize on the
                        // methods themselves is the second layer, and the load-bearing one.
                        .anyRequest().authenticated())

                // 401 rather than a redirect to a sign-in page, for every
                // unauthenticated request. With both formLogin and httpBasic
                // configured there are two candidate entry points and the choice
                // between them depends on request headers; naming one removes the
                // guesswork. It also sends no WWW-Authenticate header, so the browser
                // never puts up its own credentials box over the React app.
                // Before the anonymous filter, so a session it ends carries on as
                // anonymous for the rest of this request.
                .addFilterBefore(new AccountCheckFilter(users), AnonymousAuthenticationFilter.class)
                // Before either way of signing in, so a refused address is answered
                // before its password is checked.
                .addFilterBefore(new LoginThrottleFilter(throttle, LOGIN), UsernamePasswordAuthenticationFilter.class)

                // Spring's defaults stay (nosniff, frame denial, no caching of
                // responses); the policy above is added, and no Referer is sent on.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                // On, not off. The session lives in a cookie, so a form on another
                // site could otherwise make the browser POST here with that cookie
                // attached.
                //
                // HttpOnly, unlike the step 8 draft of this file. The client reads its
                // token from the body of GET /api/session, not from the cookie, so
                // there is no reason to let script touch it — and Spring's own advice
                // is to leave HttpOnly on unless you need to read it. The cookie is
                // the server's copy of the expected value; the body is the client's
                // copy of the value to send back.
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))

                .formLogin(form -> form
                        .loginProcessingUrl(LOGIN)
                        // 204 and nothing else. The client's next move is to re-read
                        // /api/session anyway — it has to, because signing in rotates
                        // the CSRF token — so putting the identity in this response
                        // would only mean two places that build it.
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

                // Kept for curl. With the entry point above it prompts nobody; it
                // only accepts a header that is already there, and its failures
                // count toward the sign-in throttle like the form's.
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
