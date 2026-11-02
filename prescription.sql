-- This script sets up the database schema for the prescription management application.
--
-- Indexing note: every column that is likely to be searched, filtered or joined on
-- (names, medication names, foreign keys, status, dates, stock levels) has a secondary
-- index below, so lookups stay fast as the tables grow.
--   * B-tree KEY indexes cover exact-match, prefix (LIKE 'abc%') and ORDER BY lookups.
--   * For substring search (LIKE '%abc%') on large tables, add FULLTEXT indexes instead
--     (InnoDB/MyISAM support them): e.g. FULLTEXT KEY ft_patients_name (name).
--   * FK columns are indexed explicitly even though InnoDB auto-indexes them, so the
--     indexes exist regardless of engine/constraint behaviour.

-- ############################################################################
-- WARNING: this script DESTROYS DATA. The DROP statements below remove all five
-- tables (and every prescription, patient and stock row in them) before recreating
-- them. Never point it at a database whose contents you care about; for an existing
-- database use the ALTER/CREATE INDEX statements instead of re-running this file.
-- ############################################################################

-- Drop tables if they exist to ensure a clean setup.
DROP TABLE IF EXISTS PatientMedicines;
DROP TABLE IF EXISTS Prescriptions;
DROP TABLE IF EXISTS Inventory;
DROP TABLE IF EXISTS Doctors;
DROP TABLE IF EXISTS Patients;


-- Table to store patient information.
CREATE TABLE Patients (
    patient_id VARCHAR(255) PRIMARY KEY, -- Using VARCHAR to accommodate non-numeric IDs like 'patient_123'
    name VARCHAR(255) NOT NULL,
    age INT,
    gender ENUM('Male', 'Female'),
    weight DECIMAL(5, 2), -- Weight in kg, allows for decimal values
    KEY idx_patients_name (name) -- fast search: patients by name (e.g. /api/patients/search)
);

-- Table to store doctor information.
CREATE TABLE Doctors (
    doctor_id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    specialization VARCHAR(255),
    KEY idx_doctors_name (name), -- search doctors by name
    KEY idx_doctors_specialization (specialization) -- filter doctors by specialization
);

-- Table for the chemist's inventory.
CREATE TABLE Inventory (
    item_id INT PRIMARY KEY AUTO_INCREMENT,
    item_name VARCHAR(255) NOT NULL UNIQUE, -- Each medicine name should be unique (UNIQUE already creates an index)
    quantity INT NOT NULL DEFAULT 0,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_inventory_quantity (quantity) -- fast low-stock / "which products are needed" queries
);

-- Table for prescriptions issued by a doctor.
-- This links patients, doctors, and the prescribed medication.
CREATE TABLE Prescriptions (
    prescription_id INT PRIMARY KEY AUTO_INCREMENT,
    patient_id VARCHAR(255),
    doctor_id INT,
    medication_name VARCHAR(255) NOT NULL,
    dosage VARCHAR(100),
    timing VARCHAR(100),
    date_prescribed TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status ENUM('prescribed', 'ready', 'collected') NOT NULL DEFAULT 'prescribed', -- Tracks the state of the prescription
    KEY idx_prescriptions_patient (patient_id), -- "all prescriptions of patient X" (chemist view by patient id)
    KEY idx_prescriptions_doctor (doctor_id), -- "all prescriptions written by doctor X" (doctor view)
    KEY idx_prescriptions_medication (medication_name), -- search prescriptions by medication name
    KEY idx_prescriptions_status_date (status, date_prescribed), -- filter by status, recent first (ready/collected queues)
    FOREIGN KEY (patient_id) REFERENCES Patients(patient_id),
    FOREIGN KEY (doctor_id) REFERENCES Doctors(doctor_id)
);

-- Table for personal medicines added by the patient on their dashboard.
CREATE TABLE PatientMedicines (
    medicine_id INT PRIMARY KEY AUTO_INCREMENT,
    patient_id VARCHAR(255),
    medicine_type VARCHAR(255) NOT NULL,
    dose VARCHAR(100),
    medication_time TIME, -- Stores the time for medication reminders
    KEY idx_patient_medicines_patient (patient_id), -- "all medicines of patient X"
    KEY idx_patient_medicines_type (medicine_type), -- search a patient's medicines by type
    FOREIGN KEY (patient_id) REFERENCES Patients(patient_id)
);


-- --- SAMPLE DATA FOR TESTING ---

-- Insert a sample patient
INSERT INTO Patients (patient_id, name, age, gender, weight) VALUES ('patient_123', 'John Doe', 45, 'Male', 82.5);

-- Insert a sample doctor
INSERT INTO Doctors (name, specialization) VALUES ('Dr. Alice Smith', 'General Practitioner');

-- Insert a sample prescription
INSERT INTO Prescriptions (patient_id, doctor_id, medication_name, dosage, timing, status)
VALUES ('patient_123', 1, 'Lisinopril', '10mg', 'Once a day', 'prescribed');

-- Insert sample inventory items
INSERT INTO Inventory (item_name, quantity) VALUES ('Lisinopril', 500);
INSERT INTO Inventory (item_name, quantity) VALUES ('Metformin', 300);
