package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, properties = "rehearsal.registration-enabled=false")
@Import(Security.class)
class AuthSecurityTest {
    private static final String EMAIL = "operator@example.test";
    private static final String PASSWORD = "test-passphrase";
    private static final String CREDENTIALS = "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @MockitoBean Accounts accounts;

    @Test
    void loginRotatesSessionIdAndInvalidatesTheAnonymousCsrfToken() throws Exception {
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(new Account(EMAIL, encoder.encode(PASSWORD))));
        var session = new MockHttpSession();
        var before = csrf(session);
        String anonymousSessionId = session.getId();

        mvc.perform(post("/api/auth/login").session(session).header(before.header(), before.value())
                .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(EMAIL));

        assertNotEquals(anonymousSessionId, session.getId());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).header(before.header(), before.value()))
            .andExpect(status().isForbidden());

        var after = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header(after.header(), after.value()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void closedRegistrationRejectsRequestsWithoutCreatingAccounts() throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);

        mvc.perform(post("/api/auth/register").session(session).header(token.header(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Account registration is closed. Contact your workspace operator."));

        verifyNoInteractions(accounts);
    }

    @Test
    void limitsAreIsolatedPerPeerUntilTheGlobalBudgetIsExhausted() {
        var budget = new AuthRateLimitFilter.Budget(2, 3);
        assertTrue(budget.admit("first", 59));
        assertTrue(budget.admit("first", 59));
        assertFalse(budget.admit("first", 59));
        assertTrue(budget.admit("second", 59));
        assertFalse(budget.admit("third", 59));
        assertTrue(budget.admit("first", 60));
        assertTrue(budget.admit("third", 60));
    }

    @Test
    void bearerRouteExceptionsAreLimitedToTheSubmissionPostAndResultGet() throws Exception {
        // This MVC slice has only the auth controller. A 404 means the request reached MVC.
        mvc.perform(post("/api/ci/rehearsals")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ci/rehearsals/7")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ci/rehearsals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ci/rehearsals/7/files")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ci/rehearsals/7")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/42/ci-token")).andExpect(status().isForbidden());
    }

    private Token csrf(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        return new Token(body.path("headerName").asText(), body.path("token").asText());
    }

    private record Token(String header, String value) {}
}
