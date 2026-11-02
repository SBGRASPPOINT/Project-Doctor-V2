# Project conventions

These are the working rules for this repository.

## The prescription app (root, `Admin/`, `Doctor/`, `Chemist/`, `Patient/`)

- Pages are plain HTML with external JavaScript — no frameworks, no build step.
- Every page that needs behaviour gets **its own `.js` file** next to it (`Patient/patient.js`,
  `Admin/Admin.js`), never an inline `<script>` block. Ask before creating a new file.
- These rules used to say "no CSS". That is no longer true: `Admin/Admin.css` and
  `Patient/patient.css` exist and are linked. Small, page-local CSS files are fine.

## The appointment service (`appointment/`)

- Java 25 + Spring Boot; the file has its own conventions in `appointment/README.md`.
- Request/response logic lives in framework-free classes (`web/Handlers`, services) so it can be
  unit-tested without Spring; controllers stay thin.
- Anything an operator can edit lives in the `settings` / `message_templates` tables, not in code.
