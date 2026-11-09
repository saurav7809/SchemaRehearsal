package dev.rehearsal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test void nonJsonPayloadCannotSilentlyRunAgainstTheDefaultBaseline() throws Exception {
        mvc.perform(post("/api/migrations/12/test").principal(() -> "member@example.test")
            .contentType(MediaType.TEXT_PLAIN).content("baselineId=4"))
            .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(engine);
    }
}
