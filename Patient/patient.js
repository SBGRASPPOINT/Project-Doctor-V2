document.addEventListener('DOMContentLoaded', function () {
    const addMedicineBtn = document.getElementById('add-medicine-btn');
    const saveToDbBtn = document.getElementById('save-to-db-btn');
    const medicinesContainer = document.getElementById('medicines-container');
    let medicineCount = 0;

    // The patient id must come from the page (e.g. Patient.html?patient_id=patient_123) or a
    // login later. The old fallback silently posted everything to a hardcoded 'patient_123'.
    const patientIdField = document.getElementById('patient-id');
    const patientIdFromQuery = new URLSearchParams(window.location.search).get('patient_id');
    if (patientIdField && patientIdFromQuery && !patientIdField.value) {
        patientIdField.value = patientIdFromQuery;
    }

    // Function to add a new medicine form
    const addMedicineForm = () => {
        medicineCount++;
        const newMedicineForm = document.createElement('div');
        newMedicineForm.classList.add('medicine-entry');
        newMedicineForm.innerHTML = `
            <h3>Medicine ${medicineCount}</h3>
            <div>
                <label>Medicine Type:</label>
                <input type="text" class="medicine-type" placeholder="e.g., Paracetamol" maxlength="255">
            </div>
            <div>
                <label>Dose:</label>
                <input type="text" class="medicine-dose" placeholder="e.g., 500mg" maxlength="255">
            </div>
            <div>
                <label>Medication Time:</label>
                <input type="time" class="medication-time">
            </div>
            <button type="button" class="remove-medicine-btn">Remove</button>
        `;
        medicinesContainer.appendChild(newMedicineForm);
    };

    // Function to handle saving data to the database
    const saveMedicinesToDb = async () => {
        const medicineEntries = medicinesContainer.querySelectorAll('.medicine-entry');
        const medicines = [];
        medicineEntries.forEach(entry => {
            const type = entry.querySelector('.medicine-type').value.trim();
            const dose = entry.querySelector('.medicine-dose').value.trim();
            const time = entry.querySelector('.medication-time').value;

            // Only add if at least the type is filled
            if (type) {
                medicines.push({ type, dose, time });
            }
        });

        if (medicines.length === 0) {
            alert('No medicine data to save.');
            return;
        }

        const patientId = patientIdField ? patientIdField.value.trim() : '';
        if (!patientId) {
            alert('Patient ID is missing. Open this page from the patient dashboard link.');
            return;
        }

        try {
            // Relative URL: the API is served from the same origin, so this works anywhere
            // (it used to be hardcoded to http://localhost:3000).
            const response = await fetch('/api/medicines', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ medicines, patientId }),
            });

            const result = await response.json().catch(() => ({}));

            if (response.ok) {
                medicinesContainer.innerHTML = ''; // Clear the forms after saving
                medicineCount = 0;
                alert('Added to the list'); // Display custom success message
            } else {
                throw new Error(result.message || `Failed to save data (HTTP ${response.status}).`);
            }
        } catch (error) {
            console.error('Error saving medicines:', error);
            alert(`Error: ${error.message}`);
        }
    };

    // Event Listeners
    if (addMedicineBtn) addMedicineBtn.addEventListener('click', addMedicineForm);
    if (saveToDbBtn) saveToDbBtn.addEventListener('click', saveMedicinesToDb);

    if (medicinesContainer) {
        medicinesContainer.addEventListener('click', function (e) {
            if (e.target && e.target.classList.contains('remove-medicine-btn')) {
                e.target.closest('.medicine-entry').remove();
            }
        });
    }
});
