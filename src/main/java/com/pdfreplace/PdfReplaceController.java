package com.pdfreplace;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class PdfReplaceController {
    private final PdfReplaceService service;

    public PdfReplaceController(PdfReplaceService service) {
        this.service = service;
    }

    @PostMapping(value = "/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ByteArrayResource> replace(
            @RequestParam("file") MultipartFile file,
            @RequestParam("search") String search,
            @RequestParam("replacement") String replacement,
            @RequestParam(value = "strict", defaultValue = "false") boolean strict,
            @RequestParam(value = "font", required = false) MultipartFile font
    ) throws Exception {
        PdfReplaceService.ReplacementOutput output = service.replace(file, search, replacement, strict, font);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(output.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(output.filename()).build().toString())
                .header("X-Pdf-Replacer-Matches", String.valueOf(output.result().matchesReplaced()))
                .body(new ByteArrayResource(output.bytes()));
    }
}
