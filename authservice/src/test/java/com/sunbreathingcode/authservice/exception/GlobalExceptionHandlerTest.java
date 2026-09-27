package com.sunbreathingcode.authservice.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunbreathingcode.authservice.dto.ErrorResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    // Bad login credentials must come back as 401 with the exception's own message,
    // not the empty-body 403 the /error forward used to produce.
    @Test
    void handleInvalidCredentials_returns401_withExceptionMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidCredentials(new InvalidCredentialsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid email or password");
    }

    // A rejected refresh token (bad signature, wrong type, or unknown hash) is
    // also a 401 — it's a failed authentication, not a bad request.
    @Test
    void handleInvalidRefreshToken_returns401_withExceptionMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleInvalidRefreshToken(new InvalidRefreshTokenException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid or expired refresh token");
    }

    // Registering with an email that's already taken is a conflict with existing
    // state, not a malformed request — 409 is the more precise status than 400.
    @Test
    void handleEmailAlreadyExists_returns409_withExceptionMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleEmailAlreadyExists(new EmailAlreadyExistsException("meet@sunbreathingcode.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).isEqualTo("Email already registered: meet@sunbreathingcode.com");
    }

    // A valid token whose backing user no longer exists (deleted mid-session) is
    // a 404 — the credential was fine, the resource it points to is just gone.
    @Test
    void handleUserNotFound_returns404_withExceptionMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUserNotFound(new UserNotFoundException("meet@sunbreathingcode.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isEqualTo("User not found: meet@sunbreathingcode.com");
    }

    // A failed @Valid check (e.g. blank password) should collapse every field
    // error into one readable "field: message" string, not just a generic 400.
    @Test
    void handleValidationFailure_returns400_withFieldErrorsJoined() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        bindingResult.addError(new FieldError("request", "password", "must not be blank"));

        Method dummyMethod = getClass().getDeclaredMethod("dummyValidatedMethod", String.class);
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(new MethodParameter(dummyMethod, 0), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationFailure(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage())
                .contains("email: must not be blank")
                .contains("password: must not be blank");
    }

    // Any exception we didn't explicitly map must still return clean JSON instead
    // of the broken /error path — and must never leak the exception's own message,
    // class name, or stack trace to the client.
    @Test
    void handleUnexpectedError_returns500_withGenericMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpectedError(new RuntimeException("some internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
    }

    // Exists only so the test above has a real Method to build a MethodParameter
    // from — MethodArgumentNotValidException requires one, it can't be mocked.
    @SuppressWarnings("unused")
    private void dummyValidatedMethod(String value) {}
}
