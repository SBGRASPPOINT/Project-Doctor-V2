# Admin Dashboard Documentation

## Overview

This document provides a detailed explanation of the Admin dashboard, a simple web interface designed to view data from the application's database. It consists of an HTML file for structure, a CSS file for styling, and a JavaScript file for fetching and displaying data dynamically.

---

## File Structure

The `Admin` folder contains the following files:

-   `Admin.html`: The main HTML file that provides the structure of the page.
-   `Admin.css`: The stylesheet that defines the visual appearance of the dashboard.
-   `Admin.js`: The JavaScript file that contains all the logic for interactivity and data fetching.

---

## How It Works

The admin dashboard is a read-only interface for viewing the contents of the application's database tables.

1.  The user opens the `Admin.html` page in a web browser.
2.  The page displays separate sections for each database table (Patients, Doctors, Inventory, etc.).
3.  The user clicks a **Fetch** button within a section.
4.  This action triggers a JavaScript function that sends a request to the backend server's API.
5.  The server queries the corresponding database table and returns the data as a JSON array.
6.  The JavaScript receives this data, dynamically builds an HTML table, and injects it into the page for the user to see.

---

## Scripts and Logic (`Admin.js`)

The `Admin.js` file is responsible for all the dynamic behavior of the page.

### Event Handling

-   A `DOMContentLoaded` listener ensures that the script only runs after the entire HTML page has been loaded.
-   It attaches a `click` event listener to every "Fetch" button. When a button is clicked, the script identifies which table to fetch based on the button's `data-table` attribute (e.g., `data-table="patients"`) and calls the appropriate function.

### Core Functions

-   `fetchAndDisplayData(tableName, container)`: This is an asynchronous function that manages the entire data fetching and display process. It shows a "Loading..." message, calls the backend API using the provided `tableName`, and upon receiving the data, it uses the `createTable` helper function to render the final table. It also handles any network or server errors.

-   `createTable(data)`: This function takes the JSON data returned from the server and builds an HTML `<table>` element. It automatically generates the table headers from the data's keys and then populates the table rows with the data, ensuring a structured and readable display.

### API Communication

The script makes `GET` requests to a series of backend endpoints. It assumes the server exposes an API at the path `/api/admin/{tableName}`, where `{tableName}` is dynamically replaced with the name of the table to be fetched (e.g., `/api/admin/patients`). The backend server is expected to handle these requests and return a JSON array of objects from the database.
