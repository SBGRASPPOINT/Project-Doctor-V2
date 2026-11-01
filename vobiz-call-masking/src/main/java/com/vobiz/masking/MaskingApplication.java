package com.vobiz.masking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Vobiz call-masking service: one public DID (M1) in front of two or more real agent
 * numbers, with availability routing, a call log, voicemail fallback and click-to-call
 * callbacks. See README.md and ../Vobiz-Call-Masking-Plan.md.
 */
@SpringBootApplication
public class MaskingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaskingApplication.class, args);
    }
}
