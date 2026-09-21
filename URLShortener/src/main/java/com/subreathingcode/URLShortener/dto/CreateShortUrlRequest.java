package com.subreathingcode.URLShortener.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;

@Getter
@Setter
public class CreateShortUrlRequest {

    @NotBlank(message = "url must not be blank")
    @URL(message = "url must be a valid URL")
    private String url;
}
