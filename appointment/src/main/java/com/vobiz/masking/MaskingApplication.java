package com.vobiz.masking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Call-first appointment service built on the Vobiz call-masking core: one public DID (M1)
 * in front of real agent numbers (masking preserved), plus IVR self-service booking,
 * doctor schedules/availability, and automated voice reminders. See README.md and
 * ../Vobiz-Call-Masking-Plan.md.
 */
@SpringBootApplication
@EnableScheduling
public class MaskingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaskingApplication.class, args);
    }
}
