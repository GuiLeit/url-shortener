package com.shortener.url;

import com.shortener.validation.NonRecursiveUrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@Import(NonRecursiveUrlValidator.class)
@TestPropertySource(properties = {
    "shortener.base-url=http://localhost",
    "shortener.secret-key=test-secret"
})
class CreateUrlRequestValidationTest {

    @Autowired MockMvc mvc;
    @MockBean UrlService urlService;

    @Test
    void blank_url_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ftp_scheme_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://example.com/file.txt\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void url_exceeding_2048_chars_returns_400() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(2048);
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + longUrl + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void self_referential_host_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"http://localhost/existing-code\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void valid_https_url_returns_201() throws Exception {
        when(urlService.create(anyString())).thenReturn(
            new CreateUrlResponse("Hk2p", "http://localhost/Hk2p",
                "https://example.com/page", Instant.now()));

        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/page\"}"))
            .andExpect(status().isCreated());
    }
}
