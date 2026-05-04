package com.pdfreplace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class ContactService {
    private final JavaMailSender mailSender;

    @Value("${pdfreplacer.contact.to-email:sarveshthapa007@gmail.com}")
    private String contactEmail;

    @Value("${pdfreplacer.contact.from-email:}")
    private String fromEmail;

    public ContactService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendInquiry(ContactController.ContactRequest request) {
        validate(request);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(contactEmail);
        if (fromEmail != null && !fromEmail.isBlank()) {
            message.setFrom(fromEmail);
        }
        message.setReplyTo(request.email().trim());
        message.setSubject("[PDFBolt Inquiry] " + request.subject().trim());
        message.setText(
                "Name: " + request.name().trim() + "\n"
                        + "Email: " + request.email().trim() + "\n\n"
                        + "Message:\n" + request.message().trim()
        );
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new IllegalStateException(
                    "Unable to send inquiry email. Check SMTP settings (SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, MAIL_FROM).",
                    exception
            );
        }
    }

    private static void validate(ContactController.ContactRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required.");
        }
        if (isBlank(request.name())) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (isBlank(request.email())) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (!request.email().contains("@")) {
            throw new IllegalArgumentException("Email format is invalid.");
        }
        if (isBlank(request.subject())) {
            throw new IllegalArgumentException("Subject is required.");
        }
        if (isBlank(request.message())) {
            throw new IllegalArgumentException("Message is required.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
