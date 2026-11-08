package dev.rehearsal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    static final PathPatternRequestMatcher LOGIN = PATHS.matcher(HttpMethod.POST, "/api/auth/login");
    static final PathPatternRequestMatcher REGISTER = PATHS.matcher(HttpMethod.POST, "/api/auth/register");
    static final PathPatternRequestMatcher CSRF = PATHS.matcher(HttpMethod.GET, "/api/auth/csrf");

    private final Budget login = new Budget(12, 120);
    private final Budget register = new Budget(6, 30);
    private final Budget csrf = new Budget(120, 1_200);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long second = Instant.now().getEpochSecond();
        Budget budget = budgetFor(request);
        if (budget != null && !budget.admit(request.getRemoteAddr(), second)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(60 - Math.floorMod(second, 60)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"Too many authentication requests. Try again shortly.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Budget budgetFor(HttpServletRequest request) {
        if (LOGIN.matches(request)) return login;
        if (REGISTER.matches(request)) return register;
        if (CSRF.matches(request)) return csrf;
        return null;
    }

    static final class Budget {
        private final int perPeer;
        private final int global;
        private final Map<String, Integer> peers = new HashMap<>();
        private long minute = Long.MIN_VALUE;
        private int accepted;

        Budget(int perPeer, int global) {
            this.perPeer = perPeer;
            this.global = global;
        }

        synchronized boolean admit(String peer, long epochSecond) {
            long currentMinute = Math.floorDiv(epochSecond, 60);
            if (minute != currentMinute) {
                minute = currentMinute;
                peers.clear();
                accepted = 0;
            }
            int peerAccepted = peers.getOrDefault(peer, 0);
            if (accepted >= global || peerAccepted >= perPeer) return false;
            // The global admission budget also bounds the number of distinct peer entries.
            peers.put(peer, peerAccepted + 1);
            accepted++;
            return true;
        }
    }
}
