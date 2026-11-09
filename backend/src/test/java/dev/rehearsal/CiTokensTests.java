package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CiTokensTests {
    private static final String EMAIL = "operator@example.test";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final BaselineStore baselines = mock(BaselineStore.class);
    private final CiTokens tokens = new CiTokens(jdbc,projects,baselines);

    @Test void storesOnlyTheDigestAndReturnsTheRawTokenOnce() throws Exception {
        var created=Instant.parse("2026-11-01T00:00:00Z");
        when(projects.account(EMAIL)).thenReturn(7L);
        when(jdbc.queryForList(contains("for update"),eq(Long.class),eq(42L),eq(7L))).thenReturn(List.of(42L));
        when(baselines.require(42,8)).thenReturn(new BaselineStore.Stored(
            new BaselineStore.View(8,42,"Release",created,14),"sealed_baseline"));
        when(jdbc.queryForObject(contains("insert into project_ci_tokens"),
            ArgumentMatchers.<RowMapper<Instant>>any(),eq(42L),eq(8L),any(byte[].class))).thenReturn(created);
        when(jdbc.query(contains("where t.project_id=?"),ArgumentMatchers.<RowMapper<CiTokens.View>>any(),eq(42L)))
            .thenReturn(List.of(new CiTokens.View(true,8L,"Release",created,null)));

        var issued=tokens.issue(42,8,EMAIL);
        var stored=ArgumentCaptor.forClass(byte[].class);
        verify(jdbc).queryForObject(contains("on conflict (project_id) do update"),
            ArgumentMatchers.<RowMapper<Instant>>any(),eq(42L),eq(8L),stored.capture());
        assertEquals(32,Base64.getUrlDecoder().decode(issued.token()).length);
        assertArrayEquals(digest(issued.token()),stored.getValue());
        assertFalse(issued.toString().contains(issued.token()));
        verify(projects).audit(7,"CI_TOKEN_ISSUED",42);

        var json=new ObjectMapper().findAndRegisterModules();
        assertEquals(issued.token(),json.valueToTree(issued).get("token").asText());
        var settings=json.valueToTree(tokens.settings(42,EMAIL));
        assertFalse(settings.has("token"));
        assertTrue(settings.get("configured").asBoolean());
    }

    @Test void rejectsMalformedBearersWithoutContactingTheDatabase() {
        assertUnauthorized(null);
        assertUnauthorized("Basic " + "a".repeat(43));
        assertUnauthorized("Bearer " + "a".repeat(42));
        assertUnauthorized("Bearer " + "a".repeat(43) + "=");
        verifyNoInteractions(jdbc);
    }

    @Test void authorizesOnlyADigestBoundToAPublishedBaselineInTheSameProject() throws Exception {
        String secret="a".repeat(43);
        var access=new CiTokens.Access(42,8,EMAIL);
        when(jdbc.query(contains("b.project_id=t.project_id and b.published"),
            ArgumentMatchers.<RowMapper<CiTokens.Access>>any(),eq(digest(secret))))
            .thenReturn(List.of(access)).thenReturn(List.of());

        assertEquals(access,tokens.authorize("bearer " + secret));
        assertUnauthorized("Bearer " + secret);
    }

    @Test void cannotIssueOrRevokeATokenForSomeoneElsesProject() {
        when(projects.account(EMAIL)).thenReturn(7L);
        when(jdbc.queryForList(contains("for update"),eq(Long.class),eq(42L),eq(7L))).thenReturn(List.of());

        assertEquals(404,assertThrows(ResponseStatusException.class,() -> tokens.issue(42,8,EMAIL)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,() -> tokens.revoke(42,EMAIL)).getStatusCode().value());
        verifyNoInteractions(baselines);
    }

    private void assertUnauthorized(String header) {
        assertEquals(401,assertThrows(ResponseStatusException.class,() -> tokens.authorize(header)).getStatusCode().value());
    }

    private byte[] digest(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    }
}
