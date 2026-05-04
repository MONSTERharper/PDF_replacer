package com.pdfreplace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContactController {
    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, Object>> sendInquiry(@RequestBody ContactRequest request) {
        contactService.sendInquiry(request);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Inquiry sent successfully."));
    }

    public record ContactRequest(String name, String email, String subject, String message) {
    }
}
