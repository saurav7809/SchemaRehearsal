package dev.rehearsal;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
class Security {
    @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(Accounts accounts) {
        return email -> accounts.findByEmail(email).map(a -> User.withUsername(a.email)
                .password(a.passwordHash).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }

    @Bean AuthenticationManager manager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean HttpSessionSecurityContextRepository securityContexts() {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean SecurityFilterChain chain(HttpSecurity http, HttpSessionSecurityContextRepository securityContexts,
                                    HttpSessionCsrfTokenRepository csrfTokens) throws Exception {
        var paths=PathPatternRequestMatcher.withDefaults();
        var ciSubmission=paths.matcher(HttpMethod.POST,"/api/ci/rehearsals");
        var ciResult=paths.matcher(HttpMethod.GET,"/api/ci/rehearsals/{id}");
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers(AuthRateLimitFilter.CSRF, AuthRateLimitFilter.REGISTER, AuthRateLimitFilter.LOGIN).permitAll()
                .requestMatchers(ciSubmission,ciResult).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .anyRequest().authenticated())
            .securityContext(context -> context.securityContextRepository(securityContexts))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).ignoringRequestMatchers(ciSubmission))
            .requestCache(AbstractHttpConfigurer::disable)
            .headers(headers -> headers.frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> writeError(response,
                    HttpStatus.UNAUTHORIZED, "Authentication required."))
                .accessDeniedHandler((request, response, error) -> writeError(response,
                    HttpStatus.FORBIDDEN, "Access denied or invalid CSRF token.")))
            .addFilterBefore(new AuthRateLimitFilter(), CsrfFilter.class)
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,r,a) -> r.setStatus(204)))
            .build();
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final Accounts accounts;
    private final PasswordEncoder encoder;
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository securityContexts;
    private final CsrfAuthenticationStrategy csrfRotation;
    private final boolean registrationEnabled;

    AuthController(Accounts accounts, PasswordEncoder encoder, AuthenticationManager manager,
                   HttpSessionSecurityContextRepository securityContexts, HttpSessionCsrfTokenRepository csrfTokens,
                   @Value("${rehearsal.registration-enabled:true}") boolean registrationEnabled) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.manager = manager;
        this.securityContexts = securityContexts;
        this.csrfRotation = new CsrfAuthenticationStrategy(csrfTokens);
        this.registrationEnabled = registrationEnabled;
    }

    record Credentials(@NotBlank @Email @Size(max=254) String email,
                       @NotBlank @Size(min=8,max=72) String password) {}

    @GetMapping("/csrf") CsrfToken csrf(CsrfToken token) { return token; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Credentials body) {
        if (!registrationEnabled)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Account registration is closed. Contact your workspace operator.");
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must fit in 72 UTF-8 bytes");
        if (accounts.findByEmail(email).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        accounts.saveAndFlush(new Account(email, encoder.encode(body.password())));
    }

    @PostMapping("/login") Map<String,String> login(@Valid @RequestBody Credentials body,
            HttpServletRequest request, HttpServletResponse response) {
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BadCredentialsException("Invalid credentials");
        var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
            body.email().strip().toLowerCase(Locale.ROOT), body.password()));
        request.getSession();
        request.changeSessionId();
        csrfRotation.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        return Map.of("email", authentication.getName());
    }

    @GetMapping("/me") Map<String,String> me(java.security.Principal principal) {
        return Map.of("email", principal.getName());
    }
}
