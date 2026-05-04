package com.pdfreplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

@SpringBootApplication
public class PdfReplacerWebApplication {
    public static void main(String[] args) {
        EnvFileLoader.loadIfPresent(Path.of(".env"));
        SpringApplication.run(PdfReplacerWebApplication.class, args);
    }
}
