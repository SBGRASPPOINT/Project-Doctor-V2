Software Documentation
Overview

This software is designed to collect and manage patient data, including prescriptions and associated products. The data is structured and displayed to both chemists and doctors.

Features

Chemist View: The chemist can see which products are needed based on the prescriptions.

Doctor View: The doctor can see which products are being used for each patient and the number of patients for each prescription.

HTML Pages

Patient Page: patient.html

Doctor Page: doctor.html

Chemist Page: chemist.html# My Project

---

## New project in this repo: `vobiz-call-masking/`

A completely separate **Java 25 (LTS) + Spring Boot 4.1 + Gradle 9.8 + PostgreSQL + Lombok + Log4j2**
service that implements the call-masking plan in `Vobiz-Call-Masking-Plan.md`: one public Vobiz DID (M1)
bridged to hidden agent numbers, with a call log, voicemail fallback and click-to-call callbacks.
See `vobiz-call-masking/README.md` for setup (`docker compose up -d` then `./gradlew bootRun`).
