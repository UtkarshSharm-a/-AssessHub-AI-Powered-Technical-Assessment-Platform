package com.assessment.platform.service;

import com.resend.Resend;
import com.resend.services.emails.model.SendEmailRequest;
import com.resend.services.emails.model.SendEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final Resend resend;

    public EmailService(@Value("${RESEND_API_KEY}") String apiKey) {
        this.resend = new Resend(apiKey);
    }

    @Async
    public void sendOtpEmail(String to, String otp) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .from("onboarding@resend.dev")
                    .to(to)
                    .subject("Assessment Platform - OTP Verification")
                    .text(
                            "Your OTP code is: " + otp +
                            "\n\nThis code will expire in 5 minutes." +
                            "\n\nDo not share this code with anyone."
                    )
                    .build();

            SendEmailResponse response = resend.emails().send(request);

            log.info("OTP email sent to: {}. Email ID: {}", to, response.getId());

        } catch (Exception e) {
            log.error("Failed to send OTP email to {}", to, e);
        }
    }

    @Async
    public void sendResultEmail(
            String to,
            String userName,
            String testTitle,
            int score,
            int totalMarks
    ) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .from("onboarding@resend.dev")
                    .to(to)
                    .subject("Assessment Platform - Test Results: " + testTitle)
                    .text(
                            String.format(
                                    "Dear %s,\n\n" +
                                    "Your results for \"%s\" have been released.\n\n" +
                                    "Score: %d / %d\n\n" +
                                    "Regards,\nAssessment Platform",
                                    userName,
                                    testTitle,
                                    score,
                                    totalMarks
                            )
                    )
                    .build();

            SendEmailResponse response = resend.emails().send(request);

            log.info(
                    "Result email sent to: {}. Email ID: {}",
                    to,
                    response.getId()
            );

        } catch (Exception e) {
            log.error("Failed to send result email to {}", to, e);
        }
    }
}