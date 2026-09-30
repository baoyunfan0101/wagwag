package com.wagwag.api;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidInput(MethodArgumentNotValidException error) {
        ProblemDetail result = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        result.setDetail(error.getBindingResult().getFieldErrors().stream()
            .map(field -> field.getField() + ": " + field.getDefaultMessage())
            .distinct().collect(Collectors.joining("; ")));
        return result;
    }
}
