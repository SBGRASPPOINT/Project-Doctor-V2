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

## What's in this branch

1. **`vobiz-call-masking/`** — a completely separate new project: **Java 25 (LTS) + Spring Boot 4.1 + Gradle 9.8 + PostgreSQL + Lombok + Log4j2**. It implements the call-masking plan in `Vobiz-Call-Masking-Plan.md`: one public Vobiz DID (M1) bridged to hidden agent numbers, with a call log, voicemail fallback and click-to-call callbacks. Persistence uses **Spring Data JPA** (`@Entity` classes generate the PostgreSQL schema via Hibernate — no hand-written `schema.sql`) plus **JDBC** (`JdbcTemplate`) for the native log/stats queries. Setup: `cd vobiz-call-masking && docker compose up -d && ./gradlew bootRun` — full details in `vobiz-call-masking/README.md`.
2. **Database indexes in `prescription.sql`** — secondary indexes on the entity columns most likely to be searched, filtered or joined on (details below).

## New project in this repo: `vobiz-call-masking/`

A completely separate **Java 25 (LTS) + Spring Boot 4.1 + Gradle 9.8 + PostgreSQL + Lombok + Log4j2**
service that implements the call-masking plan in `Vobiz-Call-Masking-Plan.md`: one public Vobiz DID (M1)
bridged to hidden agent numbers, with a call log, voicemail fallback and click-to-call callbacks.
See `vobiz-call-masking/README.md` for setup (`docker compose up -d` then `./gradlew bootRun`).

## Database Indexes (prescription.sql)

Secondary indexes were added on the columns most likely to be searched, filtered or joined on, so lookups stay fast as the tables grow:

- `Patients`: `idx_patients_name (name)` — patient search by name
- `Doctors`: `idx_doctors_name (name)`, `idx_doctors_specialization (specialization)`
- `Inventory`: `idx_inventory_quantity (quantity)` — low-stock checks (`item_name` is already UNIQUE-indexed)
- `Prescriptions`: `idx_prescriptions_patient (patient_id)`, `idx_prescriptions_doctor (doctor_id)`, `idx_prescriptions_medication (medication_name)`, `idx_prescriptions_status_date (status, date_prescribed)`
- `PatientMedicines`: `idx_patient_medicines_patient (patient_id)`, `idx_patient_medicines_type (medicine_type)`

These B-tree indexes cover exact-match, prefix (`LIKE 'abc%'`) and `ORDER BY` queries. For substring search (`LIKE '%abc%'`) on large tables, use a FULLTEXT index instead (see the note at the top of `prescription.sql`).
