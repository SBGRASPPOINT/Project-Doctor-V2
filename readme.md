# Project Doctor

Two applications live in this repository:

| Path | What it is | Stack |
|---|---|---|
| `appointment/` | **Call-first appointment system** on top of Vobiz: one public DID (M1), keypad IVR booking/reschedule/cancel, doctor schedules, voice reminders, plus a shared Vobiz WhatsApp inbox with staff access tokens. | Java 25, Spring Boot 4.1, PostgreSQL 17, Gradle 9.8 |
| `/` (root), `Admin/`, `Doctor/`, `Chemist/`, `Patient/` | **Prescription management**: a small MySQL API plus static HTML dashboards for doctors, chemists, patients and admins. | Node 20+, Express 5, MySQL 8, plain HTML/JS |

Start with [`appointment/README.md`](appointment/README.md) for the telephony side and
[`Vobiz-Call-Masking-Plan.md`](Vobiz-Call-Masking-Plan.md) for the design reasoning behind it.

---

## Prescription app (root)

### What each page actually does

| Page | Script | Behaviour |
|---|---|---|
| `Patient/Patient.html` | `patient.js` | Adds medicine rows and saves them via `POST /api/medicines`. Needs a patient id: open it as `Patient.html?patient_id=patient_123`. |
| `Doctor/Doctor.html` | none | A form skeleton for entering patient info and a prescription. It is **not wired to the API yet** — there is no `Doctor.js`. |
| `Chemist/Chemist.html` | none | A form skeleton for viewing prescriptions and inventory. Not wired to the API yet. |
| `Admin/Admin.html` | `Admin.js` | Read-only viewer for the five tables. Requires the admin API key (prompted on first use). |

File names are case-sensitive on Linux and on GitHub Pages: `Patient/Patient.html`, not
`patient.html`.

### Run it

```bash
npm install

# configuration (all optional for a local MySQL; see .env.example)
export DB_HOST=localhost DB_USER=root DB_PASSWORD=secret DB_NAME=prescription_app
export API_KEY=change-me          # required by /api/admin/*; empty = open (development only)
export ALLOWED_ORIGINS=           # comma-separated allow-list; empty = same-origin only

npm start                         # http://localhost:3000
curl localhost:3000/api/health    # {"status":"ok"} when the database answers
```

Create the schema first with [`prescription.sql`](prescription.sql) — read the warning at the top:
it **drops** the five tables it creates.

### API

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/health` | – | process + database liveness |
| `POST /api/medicines` | – | `{patientId, medicines:[{type,dose,time}]}` → inserts `PatientMedicines` rows |
| `GET /api/admin/{patients,doctors,prescriptions,patientmedicines,inventory}` | `x-api-key` | paginated table dump: `{items, count, limit, offset}` (`?limit` ≤ 500, `?offset`) |

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | HTTP port |
| `DB_HOST` / `DB_PORT` | `localhost` / `3306` | MySQL host/port |
| `DB_USER` / `DB_PASSWORD` / `DB_NAME` | `root` / – / `prescription_app` | MySQL credentials (pooled, 10 connections by default) |
| `DB_POOL_SIZE` | `10` | Pool size |
| `API_KEY` | empty | Required as `x-api-key` for `/api/admin/*`. Empty = unauthenticated, logs a warning. |
| `ALLOWED_ORIGINS` | empty | Comma-separated CORS allow-list. Empty = same-origin only. |

---

## Tests and CI

```bash
npm test                      # node --check on the JavaScript files
cd appointment && ./gradlew test
```

`.github/workflows/ci.yml` builds and tests the Java service, boots it against a real PostgreSQL
service container, and syntax-checks every JavaScript file.

## Repository layout notes

- `prescription.sql` is MySQL DDL for the prescription app (it drops and recreates five tables).
- `gemini.md` records the project's original "HTML + JavaScript only" convention; the committed
  CSS/Java under `appointment/` is the documented exception.
- `.gitignore` covers `node_modules`, build outputs and `.env` files; there is no `.env` committed.

## License

ISC — see [`LICENSE`](LICENSE).
