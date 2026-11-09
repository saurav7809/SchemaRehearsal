package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
class Analyzer implements ApplicationRunner {
    record Result(boolean valid,String error,List<Risk.Finding> findings,int statementCount) {}
    private final ObjectMapper json;
    private final String python;
    private final Path script;
    private final boolean production;
    private final Semaphore workers = new Semaphore(2);

    Analyzer(ObjectMapper json,Environment environment,@Value("${SR_PYTHON:python}") String python,
             @Value("${SR_ANALYZER:../analysis/analyze.py}") String script) {
        this.json=json;
        this.python=python;
        this.script=Path.of(script).toAbsolutePath().normalize();
        production=environment.matchesProfiles("production");
        if (production && (!Path.of(python).isAbsolute() || !Files.isRegularFile(Path.of(python))
                || !Path.of(script).isAbsolute() || !Files.isRegularFile(this.script)))
            throw new IllegalArgumentException("Production requires absolute SR_PYTHON and SR_ANALYZER file paths.");
    }

    @Override public void run(ApplicationArguments args) {
        if (production && !inspect("SELECT 1",true).valid())
            throw new IllegalStateException("The production SQL analyzer failed its startup check.");
    }

    Result inspect(String sql,boolean probe) {
        return inspectRequest(Map.of("sql",sql,"probe",probe));
    }

    Result inspect(WorkspaceStore.Migration migration) {
        if (migration.files().size()==1) return inspect(migration.files().getFirst().sql(),false);
        return inspectRequest(Map.of("files",migration.files()));
    }

    private Result inspectRequest(Map<String,?> request) {
        if (!workers.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"The SQL analyzer is busy. Try again shortly.");
        Path input=null;
        Path output=null;
        Process process=null;
        try {
            input=Files.createTempFile("rehearsal-analysis-in-",".json");
            output=Files.createTempFile("rehearsal-analysis-out-",".json");
            Files.write(input,json.writeValueAsBytes(request));
            var command=production ? List.of(python,"-I",script.toString()) : List.of(python,script.toString());
            var builder=new ProcessBuilder(command).redirectInput(input.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile());
            var allowed=Set.of("PATH","PATHEXT","SYSTEMROOT","WINDIR","COMSPEC","TEMP","TMP","LANG","LC_ALL");
            builder.environment().keySet().removeIf(name -> !allowed.contains(name.toUpperCase(Locale.ROOT))
                && (production || !name.equalsIgnoreCase("PYTHONPATH")));
            process=builder.start();
            if (!process.waitFor(8,TimeUnit.SECONDS) || process.exitValue()!=0 || Files.size(output)>1_000_000)
                throw new IOException("Invalid analyzer response");
            return json.readValue(output.toFile(),Result.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException e) {
            throw unavailable();
        } finally {
            if (process!=null && process.isAlive()) process.destroyForcibly();
            remove(input);
            remove(output);
            workers.release();
        }
    }

    private static void remove(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException e) { file.toFile().deleteOnExit(); }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "SQL analyzer unavailable. Verify the Python executable and SQLGlot installation.");
    }
}
