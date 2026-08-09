package com.assessment.platform.controller;
import com.assessment.platform.service.OtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestOtpController {

    private final OtpService otpService;

    @GetMapping("/otp")
    public ResponseEntity<?> getLatestOtp(@RequestParam String email) {
        String otp = otpService.getLatestOtp(email);

        if (otp == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "email", email,
                "otp", otp
        ));
    }
}