package com.shortener.url;

import com.shortener.validation.NonRecursiveUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public class CreateUrlRequest {

    @NotBlank
    @URL(regexp = "^https?://.*")
    @Size(max = 2048)
    @NonRecursiveUrl
    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
