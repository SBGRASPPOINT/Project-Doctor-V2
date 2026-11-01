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

1. **`appointment/`** — a call-first **appointment booking system** built on the Vobiz call-masking service (plan in `Vobiz-Call-Masking-Plan.md`): **Java 25 (LTS) + Spring Boot 4.1 + Gradle 9.8 + PostgreSQL + Lombok + Log4j2**. Patients dial the single public DID (M1) and book / reschedule / cancel by keypad IVR; callers and staff only ever see M1 (masking preserved); automated voice reminder calls (T-24h, T-2h) plus immediate change-notification calls; voicemail → callback queue and click-to-call from the original masking service keep working. Persistence uses **Spring Data JPA** (`@Entity` classes generate the PostgreSQL schema via Hibernate — no `schema.sql`) plus **JDBC** (`JdbcTemplate`) for native list/stats queries. Setup: `cd appointment && docker compose up -d && ./gradlew bootRun` — full details in `appointment/README.md`.
2. **Database indexes in `prescription.sql`** — secondary indexes on the entity columns most likely to be searched, filtered or joined on (details below).

## New project in this repo: `appointment/`

The call-masking service (one public Vobiz DID M1 bridged to hidden agent numbers, call log,
voicemail fallback, click-to-call callbacks) extended into an appointment system: IVR self-service
booking on the inbound call, doctor schedules/availability, reminder engine over outbound voice
calls, and an ops console. See `appointment/README.md` for setup (`docker compose up -d` then `./gradlew bootRun`).

## Database Indexes (prescription.sql)

Secondary indexes were added on the columns most likely to be searched, filtered or joined on, so lookups stay fast as the tables grow:

- `Patients`: `idx_patients_name (name)` — patient search by name
- `Doctors`: `idx_doctors_name (name)`, `idx_doctors_specialization (specialization)`
- `Inventory`: `idx_inventory_quantity (quantity)` — low-stock checks (`item_name` is already UNIQUE-indexed)
- `Prescriptions`: `idx_prescriptions_patient (patient_id)`, `idx_prescriptions_doctor (doctor_id)`, `idx_prescriptions_medication (medication_name)`, `idx_prescriptions_status_date (status, date_prescribed)`
- `PatientMedicines`: `idx_patient_medicines_patient (patient_id)`, `idx_patient_medicines_type (medicine_type)`

These B-tree indexes cover exact-match, prefix (`LIKE 'abc%'`) and `ORDER BY` queries. For substring search (`LIKE '%abc%'`) on large tables, use a FULLTEXT index instead (see the note at the top of `prescription.sql`).
