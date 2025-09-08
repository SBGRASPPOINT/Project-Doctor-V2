document.addEventListener('DOMContentLoaded', function () {
    const addMedicineBtn = document.getElementById('add-medicine-btn');
    const saveToDbBtn = document.getElementById('save-to-db-btn');
    const medicinesContainer = document.getElementById('medicines-container');
    let medicineCount = 0;

    // Function to add a new medicine form
    const addMedicineForm = () => {
        medicineCount++;
        const newMedicineForm = document.createElement('div');
        newMedicineForm.classList.add('medicine-entry');
        newMedicineForm.innerHTML = `
            <h3>Medicine ${medicineCount}</h3>
            <div>
                <label>Medicine Type:</label>
                <input type="text" class="medicine-type" placeholder="e.g., Paracetamol">
            </div>
            <div>
                <label>Dose:</label>
                <input type="text" class="medicine-dose" placeholder="e.g., 500mg">
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
            const type = entry.querySelector('.medicine-type').value;
            const dose = entry.querySelector('.medicine-dose').value;
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

        // A patient ID is needed. For now, we'll use a hardcoded value.
        // In a real app, this would come from a login system.
        const patientId = document.getElementById('patient-id').value || 'patient_123';

        try {
            const response = await fetch('http://localhost:3000/api/medicines', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ medicines, patientId }),
            });

            const result = await response.json();

            if (response.ok) {
                medicinesContainer.innerHTML = ''; // Clear the forms after saving
                alert('Added to the list'); // Display custom success message
            } else {
                throw new Error(result.message || 'Failed to save data.');
            }
        } catch (error) {
            console.error('Error saving medicines:', error);
            alert(`Error: ${error.message}`);
        }
    };

    // Event Listeners
    addMedicineBtn.addEventListener('click', addMedicineForm);
    saveToDbBtn.addEventListener('click', saveMedicinesToDb);

    medicinesContainer.addEventListener('click', function (e) {
        if (e.target && e.target.classList.contains('remove-medicine-btn')) {
            e.target.closest('.medicine-entry').remove();
        }
    });
});