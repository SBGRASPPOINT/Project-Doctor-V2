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

// Endpoint to receive medicine data
app.post('/api/medicines', (req, res) => {
    const medicines = req.body.medicines;
    const patientId = req.body.patientId; // Assuming a patient ID is sent

    if (!medicines || !Array.isArray(medicines) || medicines.length === 0) {
        return res.status(400).send('Medicine data is required and must be an array.');
    }

    const query = 'INSERT INTO medicines (patient_id, medicine_type, dose, medication_time) VALUES ?';

    // Format the data for bulk insert
    const values = medicines.map(med => [patientId, med.type, med.dose, med.time]);

    db.query(query, [values], (err, result) => {
        if (err) {
            console.error('Error inserting data into database:', err);
            return res.status(500).send('Failed to save medicine data.');
        }
        res.status(201).send({ message: 'Medicine data saved successfully!', insertedRows: result.affectedRows });
    });
});

app.listen(port, () => {
    console.log(`Server is running on http://localhost:${port}`);
});
