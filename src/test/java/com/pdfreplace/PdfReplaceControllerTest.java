package com.pdfreplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "boltreplacer.limits.max-pages=1",
        "boltreplacer.limits.max-search-length=20",
        "boltreplacer.limits.max-replacement-length=20"
})
class PdfReplaceControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void rejectsPdfOverConfiguredPageLimit() throws Exception {
        Path input = tempDir.resolve("two-pages.pdf");
        PdfTestSupport.createPdfWithPages(input, 2);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "two-pages.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(input)
        );

        mockMvc.perform(multipart("/api/replace")
                        .file(file)
                        .param("search", "Page")
                        .param("replacement", "P")
                        .param("matchMode", "exact")
                        .param("replaceScope", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("exceeding the limit")));
    }

    @Test
    void rejectsInvalidNthScopeRequest() throws Exception {
        Path input = tempDir.resolve("single-page.pdf");
        PdfTestSupport.createPdfWithPages(input, 1);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "single-page.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(input)
        );

        mockMvc.perform(multipart("/api/replace")
                        .file(file)
                        .param("search", "Page")
                        .param("replacement", "P")
                        .param("matchMode", "exact")
                        .param("replaceScope", "nth"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Occurrence index")));
    }

    @Test
    void returnsFontDiagnosticWhenReplacementGlyphCannotBeEncoded() throws Exception {
        Path input = tempDir.resolve("glyph.pdf");
        PdfTestSupport.createPdfWithText(input, "A");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "glyph.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(input)
        );

        mockMvc.perform(multipart("/api/replace")
                        .file(file)
                        .param("search", "A")
                        .param("replacement", "\uD83D\uDE42")
                        .param("matchMode", "exact")
                        .param("replaceScope", "all"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cannot be encoded")));
    }

    @Test
    void includesMatchHeadersOnSuccess() throws Exception {
        Path input = tempDir.resolve("success.pdf");
        PdfTestSupport.createPdfWithText(input, "Invoice invoice");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "success.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(input)
        );

        mockMvc.perform(multipart("/api/replace")
                        .file(file)
                        .param("search", "invoice")
                        .param("replacement", "bill")
                        .param("matchMode", "caseInsensitive")
                        .param("replaceScope", "first"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Bolt-Replacer-Matches"))
                .andExpect(header().exists("X-Bolt-Replacer-Matches-Found"))
                .andExpect(header().exists("X-Bolt-Replacer-Style-Preserved"))
                .andExpect(header().exists("X-Bolt-Replacer-Style-Fallback"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }
}
