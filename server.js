const express = require('express');
const mysql = require('mysql2/promise');
const cors = require('cors');

/*
 * Legacy prescription API (MySQL) behind the Doctor/Chemist/Patient/Admin pages.
 *
 * Changed from the original version:
 *   - configuration comes from the environment (the committed file had `YOUR_PASSWORD`
 *     literals, and nothing ever read a .env file even though .gitignore lists one);
 *   - one pooled, health-checked connection instead of a single mysql.createConnection
 *     that never reconnects;
 *   - the admin table dump requires an API key and is paginated (it used to return every
 *     row of five tables to anyone who could reach the port);
 *   - CORS is allow-listed instead of `cors()` for every origin;
 *   - input is validated and errors no longer echo SQL/driver text.
 */

const PORT = Number(process.env.PORT || 3000);
const API_KEY = (process.env.API_KEY || '').trim();
// Comma-separated list, e.g. "https://clinic.example.com". Empty = same-origin only.
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '')
    .split(',')
    .map((o) => o.trim())
    .filter(Boolean);

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 500;

if (!API_KEY) {
    console.warn('[warn] API_KEY is empty — the admin endpoints are unauthenticated (development only).');
}

const app = express();
app.disable('x-powered-by');

// Same-origin by default; only reflect origins explicitly configured.
app.use(cors(ALLOWED_ORIGINS.length ? { origin: ALLOWED_ORIGINS } : { origin: false }));
app.use(express.json({ limit: '256kb' }));

// --- DATABASE CONNECTION (pooled) ---
const pool = mysql.createPool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || 'root',
    password: process.env.DB_PASSWORD || '',
    database: process.env.DB_NAME || 'prescription_app',
    waitForConnections: true,
    connectionLimit: Number(process.env.DB_POOL_SIZE || 10),
    queueLimit: 0,
    connectTimeout: 10000,
});

async function pingDatabase() {
    const conn = await pool.getConnection();
    try {
        await conn.ping();
    } finally {
        conn.release();
    }
}

// --- helpers ---
const E164 = /^\+[1-9]\d{6,14}$/;

function requireApiKey(req, res, next) {
    if (!API_KEY) return next();               // development: no key configured
    if (req.get('x-api-key') === API_KEY) return next();
    res.status(401).json({ message: 'Missing or invalid API key (x-api-key).' });
}

function parseLimit(value) {
    const n = Number.parseInt(value, 10);
    if (!Number.isFinite(n) || n <= 0) return DEFAULT_LIMIT;
    return Math.min(n, MAX_LIMIT);
}

function parseOffset(value) {
    const n = Number.parseInt(value, 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
}

// --- API ENDPOINTS ---

// Liveness probe: the process is up and the database answers.
app.get('/api/health', async (req, res) => {
    try {
        await pingDatabase();
        res.json({ status: 'ok' });
    } catch (err) {
        console.error('Health check failed:', err.message);
        res.status(503).json({ status: 'degraded' });
    }
});

// Save medicines added by a patient.
app.post('/api/medicines', async (req, res) => {
    const { medicines, patientId } = req.body || {};

    if (!Array.isArray(medicines) || medicines.length === 0) {
        return res.status(400).json({ message: 'Medicine data is required and must be a non-empty array.' });
    }
    if (medicines.length > 100) {
        return res.status(400).json({ message: 'Too many medicines in one request (max 100).' });
    }
    if (typeof patientId !== 'string' || patientId.trim() === '') {
        return res.status(400).json({ message: 'Patient ID is required.' });
    }

    const invalid = medicines.find(
        (m) => !m || typeof m.type !== 'string' || m.type.trim() === ''
    );
    if (invalid) {
        return res.status(400).json({ message: 'Every medicine needs a non-empty "type".' });
    }

    const values = medicines.map((m) => [
        patientId.trim(),
        m.type.trim().slice(0, 255),
        m.dose == null ? null : String(m.dose).trim().slice(0, 255),
        m.time || null,
    ]);
    const query = 'INSERT INTO PatientMedicines (patient_id, medicine_type, dose, medication_time) VALUES ?';

    try {
        const [result] = await pool.query(query, [values]);
        res.status(201).json({ message: 'Medicine data saved successfully!', insertedRows: result.affectedRows });
    } catch (err) {
        // Unknown patient id hits the FK; report it as a client error, not a 500.
        if (err.code === 'ER_NO_REFERENCED_ROW_2' || err.code === 'ER_NO_REFERENCED_ROW') {
            return res.status(404).json({ message: 'Unknown patient: ' + patientId });
        }
        console.error('Error inserting data into PatientMedicines table:', err.message);
        res.status(500).json({ message: 'Failed to save medicine data.' });
    }
});

// --- ADMIN PANEL API ENDPOINTS ---

// Whitelist: table names can never be interpolated from user input.
const ADMIN_TABLES = {
    patients: 'Patients',
    doctors: 'Doctors',
    prescriptions: 'Prescriptions',
    patientmedicines: 'PatientMedicines',
    inventory: 'Inventory',
};

const createAdminFetchEndpoint = (key) => {
    const tableName = ADMIN_TABLES[key];
    app.get(`/api/admin/${key}`, requireApiKey, async (req, res) => {
        const limit = parseLimit(req.query.limit);
        const offset = parseOffset(req.query.offset);
        try {
            const [rows] = await pool.query(
                `SELECT * FROM ${tableName} LIMIT ? OFFSET ?`,
                [limit, offset]
            );
            res.json({ items: rows, count: rows.length, limit, offset });
        } catch (err) {
            console.error(`Error fetching data from ${tableName}:`, err.message);
            res.status(500).json({ message: `Failed to fetch data from ${tableName}.` });
        }
    });
};

Object.keys(ADMIN_TABLES).forEach(createAdminFetchEndpoint);

// Unknown API route: JSON 404 rather than Express' HTML page.
app.use('/api', (req, res) => res.status(404).json({ message: 'Not found: ' + req.method + ' ' + req.originalUrl }));

// Last-resort error handler: log the detail, return a generic message.
app.use((err, req, res, next) => {   // eslint-disable-line no-unused-vars
    console.error('Unhandled error:', err);
    if (err && err.type === 'entity.too.large') {
        return res.status(413).json({ message: 'Request body too large.' });
    }
    if (err && err.type === 'entity.parse.failed') {
        return res.status(400).json({ message: 'Malformed JSON body.' });
    }
    res.status(500).json({ message: 'Internal error.' });
});

if (require.main === module) {
    app.listen(PORT, '0.0.0.0', () => {
        console.log(`Server listening on http://0.0.0.0:${PORT}`);
    });
    pingDatabase()
        .then(() => console.log('Database connection OK.'))
        .catch((err) => console.error('Database connection failed:', err.message));
}

module.exports = app;
