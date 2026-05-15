package com.pdfreplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/** Spring Boot host for PDFBolt tooling; primary live tool is bolt-replace (PDF text replacement API). */
@SpringBootApplication
public class PdfBoltWebApplication {
    public static void main(String[] args) {
        EnvFileLoader.loadIfPresent(Path.of(".env"));
        SpringApplication.run(PdfBoltWebApplication.class, args);
    }
}
