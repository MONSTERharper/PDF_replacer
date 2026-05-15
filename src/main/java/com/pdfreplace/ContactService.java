package com.pdfreplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class ContactService {
    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final JavaMailSender mailSender;

    @Value("${boltreplacer.contact.to-email:sarveshthapa007@gmail.com}")
    private String contactEmail;

    @Value("${boltreplacer.contact.from-email:}")
    private String fromEmail;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${boltreplacer.contact.log-only:false}")
    private boolean contactLogOnly;

    public ContactService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * @return true if an SMTP message was sent; false if log-only mode recorded the inquiry instead
     */
    public boolean sendInquiry(ContactController.ContactRequest request) {
        validate(request);

        String body = "Name: " + request.name().trim() + "\n"
                + "Email: " + request.email().trim() + "\n\n"
                + "Message:\n" + request.message().trim();

        if (contactLogOnly) {
            log.info(
                    "[PDFBolt contact, log-only] to={} reply-to={} subject={}\n{}",
                    contactEmail,
                    request.email().trim(),
                    request.subject().trim(),
                    body
            );
            return false;
        }

        if (mailHost == null || mailHost.isBlank()) {
            throw new IllegalArgumentException(
                    "Contact email is not configured: SMTP_HOST is empty. Copy .env.example to .env, set "
                            + "SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, and MAIL_FROM. "
                            + "For local testing without SMTP, set CONTACT_LOG_ONLY=true."
            );
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(contactEmail);
        if (fromEmail != null && !fromEmail.isBlank()) {
            message.setFrom(fromEmail);
        }
        message.setReplyTo(request.email().trim());
        message.setSubject("[PDFBolt Inquiry] " + request.subject().trim());
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            log.warn("SMTP send failed: {}", exception.toString());
            throw new IllegalStateException(
                    "Unable to send inquiry email. Check SMTP settings (SMTP_HOST, SMTP_PORT, SMTP_USERNAME, "
                            + "SMTP_PASSWORD, MAIL_FROM). For Gmail use an App Password for SMTP_PASSWORD.",
                    exception
            );
        }
        return true;
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
