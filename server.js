const express = require('express');
const mysql = require('mysql2');
const cors = require('cors');

const app = express();
const port = 3000;

// IMPORTANT: Enable CORS for all routes to allow frontend requests
app.use(cors());

// Middleware to parse JSON bodies
app.use(express.json());

// --- DATABASE CONNECTION ---
// !!! IMPORTANT !!!
// Replace these with your actual MySQL database credentials.
const db = mysql.createConnection({
    host: 'localhost',
    user: 'root', // or your mysql username
    password: 'YOUR_PASSWORD', // your mysql password
    database: 'YOUR_DATABASE_NAME' // the name of your database
});

db.connect(err => {
    if (err) {
        console.error('Error connecting to MySQL database:', err);
        return;
    }
    console.log('Successfully connected to MySQL database.');
});

// --- API ENDPOINTS ---

// Endpoint to save medicines added by a patient
app.post('/api/medicines', (req, res) => {
    const { medicines, patientId } = req.body;

    if (!medicines || !Array.isArray(medicines) || medicines.length === 0) {
        return res.status(400).json({ message: 'Medicine data is required and must be an array.' });
    }
    if (!patientId) {
        return res.status(400).json({ message: 'Patient ID is required.' });
    }

    // Corrected table name to 'PatientMedicines'
    const query = 'INSERT INTO PatientMedicines (patient_id, medicine_type, dose, medication_time) VALUES ?';

    // Format the data for bulk insert
    const values = medicines.map(med => [patientId, med.type, med.dose, med.time || null]);

    db.query(query, [values], (err, result) => {
        if (err) {
            console.error('Error inserting data into PatientMedicines table:', err);
            return res.status(500).json({ message: 'Failed to save medicine data.' });
        }
        res.status(201).json({ message: 'Medicine data saved successfully!', insertedRows: result.affectedRows });
    });
});


// --- ADMIN PANEL API ENDPOINTS ---

// Generic function to create a GET endpoint for a table
const createAdminFetchEndpoint = (tableName) => {
    app.get(`/api/admin/${tableName.toLowerCase()}`, (req, res) => {
        // Note: Whitelisting is used to prevent SQL injection
        const allowedTables = ['Patients', 'Doctors', 'Prescriptions', 'PatientMedicines', 'Inventory'];
        if (!allowedTables.includes(tableName)) {
            return res.status(400).send('Invalid table name.');
        }

        db.query(`SELECT * FROM ${tableName}`, (err, results) => {
            if (err) {
                console.error(`Error fetching data from ${tableName}:`, err);
                return res.status(500).send(`Failed to fetch data from ${tableName}.`);
            }
            res.json(results);
        });
    });
};

// Create admin endpoints for each table
createAdminFetchEndpoint('Patients');
createAdminFetchEndpoint('Doctors');
createAdminFetchEndpoint('Prescriptions');
createAdminFetchEndpoint('PatientMedicines');
createAdminFetchEndpoint('Inventory');


app.listen(port, () => {
    console.log(`Server is running on http://localhost:${port}`);
});
