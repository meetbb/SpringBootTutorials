package com.sunbreathingcode.PaginationDemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Unit test — runs the Bean Validation rules directly on PageRequestDto,
// without starting Spring or hitting a controller. This is the same check
// that fires automatically when @Valid rejects a bad GET /items request;
// testing it here confirms the rule itself is correct, independent of how
// Spring wires it in.
class PageRequestDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    // Scenario: defaults (no page/size set) should always be valid — this is
    // the "client omitted everything" case from Checkpoint 4.
    @Test
    void defaultsAreValid() {
        PageRequestDto request = new PageRequestDto();

        Set<ConstraintViolation<PageRequestDto>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // Scenario: a client sends a negative page number (e.g. page=-1). This
    // should be rejected — a negative page has no meaning.
    @Test
    void rejectsNegativePage() {
        PageRequestDto request = new PageRequestDto();
        request.setPage(-1);

        Set<ConstraintViolation<PageRequestDto>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("page must be >= 0");
    }

    // Scenario: a client asks for an unbounded page size (e.g. size=999999),
    // which defeats the entire point of pagination. The cap (100) exists
    // specifically to stop this.
    @Test
    void rejectsSizeAboveCap() {
        PageRequestDto request = new PageRequestDto();
        request.setSize(999999);

        Set<ConstraintViolation<PageRequestDto>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("size must be between 1 and 100");
    }

    // Scenario: a client asks for size=0 — technically "small," not large,
    // but still meaningless (a page with zero rows isn't a valid request).
    @Test
    void rejectsSizeBelowMinimum() {
        PageRequestDto request = new PageRequestDto();
        request.setSize(0);

        Set<ConstraintViolation<PageRequestDto>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("size must be between 1 and 100");
    }
}
