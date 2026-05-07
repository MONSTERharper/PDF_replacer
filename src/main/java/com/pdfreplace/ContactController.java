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
        boolean emailed = contactService.sendInquiry(request);
        String msg = emailed
                ? "Inquiry sent successfully."
                : "Inquiry recorded (CONTACT_LOG_ONLY=true; email not sent—see server logs).";
        return ResponseEntity.ok(Map.of("status", "ok", "message", msg));
    }

    public record ContactRequest(String name, String email, String subject, String message) {
    }
}
