package dev.rehearsal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
@AutoConfigureMockMvc(addFilters=false)
class RehearsalRequestTests {
    @Autowired MockMvc mvc;
    @MockitoBean RunEngine engine;
    @MockitoBean WorkspaceStore store;
    @MockitoBean Analyzer analyzer;
    @MockitoBean ReportPdf pdf;
    @MockitoBean DemoService demo;
    @MockitoBean BaselineStore baselines;

    @Test void legacyBodylessFormRequestStillStartsWithTheSharedFixture() throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType("application/x-www-form-urlencoded;charset=UTF-8"))
            .andExpect(status().isAccepted());
        verify(engine).submit(12,"member@example.test",null);
    }

    @Test void jsonRequestSelectsTheRequestedBaseline() throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"baselineId":4,"saveBaselineAs":"Next release"}
                """)).andExpect(status().isAccepted());
        verify(engine).submit(12,"member@example.test",new RunEngine.Request(4L,"Next release"));
    }

    @Test void acceptsAnOptionalPublicTableForLockImpact() throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"baselineId":4,"lockImpactTable":"users"}
                """)).andExpect(status().isAccepted());
        verify(engine).submit(12,"member@example.test",new RunEngine.Request(4L,null,"users"));
    }

    @ParameterizedTest
    @ValueSource(strings={"public.users","users; drop","USERS",""})
    void rejectsInvalidLockImpactTableNames(String table) throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"lockImpactTable":"%s"}
                """.formatted(table))).andExpect(status().isBadRequest());
        verifyNoInteractions(engine);
    }

    @Test void nonJsonPayloadCannotSilentlyRunAgainstTheDefaultBaseline() throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType(MediaType.TEXT_PLAIN).content("baselineId=4"))
            .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(engine);
    }

    @Test void acceptsZeroAsAnExactExpectedScalar() throws Exception {
        when(analyzer.inspect("SELECT COUNT(*) FROM users",true))
            .thenReturn(new Analyzer.Result(true,null,List.of(),1));
        mvc.perform(post("/api/projects/12/queries").principal(() -> "member@example.test")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Expected count","generation":"NEW","sql":"SELECT COUNT(*) FROM users",
                 "assertion":{"kind":"SCALAR_EQUALS","expected":0}}
                """)).andExpect(status().isCreated());
        verify(store).addProbe(12,"member@example.test",new WorkspaceStore.ProbeInput(
            "Expected count","NEW","SELECT COUNT(*) FROM users",new WorkspaceStore.AssertionInput("SCALAR_EQUALS",0L)));
    }

    @ParameterizedTest
    @ValueSource(strings={"1.5","\"1\"","null","9007199254740992"})
    void rejectsNonIntegerAndOutOfRangeExpectations(String expected) throws Exception {
        mvc.perform(post("/api/projects/12/queries").principal(() -> "member@example.test")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Expected count","generation":"NEW","sql":"SELECT COUNT(*) FROM users",
                 "assertion":{"kind":"SCALAR_EQUALS","expected":%s}}
                """.formatted(expected))).andExpect(status().isBadRequest());
        verifyNoInteractions(analyzer,store);
    }
}
