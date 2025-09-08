# Patient Dashboard Documentation

## Overview

This document provides a detailed explanation of the Patient dashboard. This interface allows patients to view their prescriptions, manage a personal list of medicines, and request refills. It is composed of an HTML file for the structure, a CSS file for styling, and a JavaScript file for client-side logic.

---

## File Structure

The `Patient` folder contains the following files:

-   `Patient.html`: The main HTML file that provides the structure of the patient dashboard.
-   `patient.css`: The stylesheet that defines the visual appearance of the dashboard.
-   `patient.js`: The JavaScript file that contains all the logic for dynamic functionality.

---

## How It Works

The Patient Dashboard provides several key features for managing personal health information.

1.  **View Prescriptions**: A section is dedicated to displaying the patient's official prescriptions as entered by a doctor. This area is typically read-only.

2.  **Manage Personal Medicines**: This is the core interactive feature of the dashboard.
    -   A patient can click the **Add Medicine** button to dynamically generate a new form on the page.
    -   They can then enter the medicine type, dose, and a specific time for medication reminders.
    -   Each added medicine form has a **Remove** button to delete it.
    -   After adding one or more medicines, the patient can click **Save All Medicines to Database**. This action gathers all the information from the forms and sends it to the backend server to be stored.

3.  **Request Refill**: A simple form allows the patient to send a request for a prescription refill.

---

## Scripts and Logic (`patient.js`)

The `patient.js` file handles the interactive elements of the page, especially the management of personal medicines.

### Event Handling

-   **Add Medicine**: A `click` event listener on the `add-medicine-btn` button calls the `addMedicineForm` function to create and display a new set of input fields for a medicine.
-   **Save Medicines**: A `click` event listener on the `save-to-db-btn` button calls the `saveMedicinesToDb` function to collect and send the data to the server.
-   **Remove Medicine**: A `click` event listener on the main `medicines-container` watches for clicks on any `remove-medicine-btn`. If a remove button is clicked, it removes the entire parent form for that medicine from the page.

### Core Functions

-   `addMedicineForm()`: This function dynamically creates the HTML for a new medicine entry (including input fields for type, dose, and time, and a remove button) and appends it to the main container div.

-   `saveMedicinesToDb()`: This is an asynchronous function that gathers all the data from the dynamically added medicine forms, constructs an array of medicine objects, and sends this data to the backend API via a `POST` request. It also handles displaying success or error messages to the user as an alert.

### API Communication

The script makes a `POST` request to the backend server at the endpoint `http://localhost:3000/api/medicines`. The body of this request contains the patient's ID and the array of medicine objects that the user entered. This is how the patient's personal medication list is saved to the database.
