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

Database Indexes (prescription.sql)

Secondary indexes were added on the columns most likely to be searched, filtered or joined on, so lookups stay fast as the tables grow:

- `Patients`: `idx_patients_name (name)` — patient search by name
- `Doctors`: `idx_doctors_name (name)`, `idx_doctors_specialization (specialization)`
- `Inventory`: `idx_inventory_quantity (quantity)` — low-stock checks (`item_name` is already UNIQUE-indexed)
- `Prescriptions`: `idx_prescriptions_patient (patient_id)`, `idx_prescriptions_doctor (doctor_id)`, `idx_prescriptions_medication (medication_name)`, `idx_prescriptions_status_date (status, date_prescribed)`
- `PatientMedicines`: `idx_patient_medicines_patient (patient_id)`, `idx_patient_medicines_type (medicine_type)`

These B-tree indexes cover exact-match, prefix (`LIKE 'abc%'`) and `ORDER BY` queries. For substring search (`LIKE '%abc%'`) on large tables, use a FULLTEXT index instead (see the note at the top of `prescription.sql`).
