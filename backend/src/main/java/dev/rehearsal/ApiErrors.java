package dev.rehearsal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> authentication() {
        return ResponseEntity.status(401).body(Map.of("message","Invalid email or password"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var field = e.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(Map.of("message",field == null ? "Invalid request" : "Invalid " + field.getField()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("message","The resource changed or conflicts with an existing entry"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size() {
        return ResponseEntity.status(413).body(Map.of("message","SQL files are limited to 256 KiB"));
    }
}