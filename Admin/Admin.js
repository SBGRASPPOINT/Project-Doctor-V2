document.addEventListener('DOMContentLoaded', () => {
    const buttons = document.querySelectorAll('button[data-table]');

    buttons.forEach(button => {
        button.addEventListener('click', () => {
            const tableName = button.dataset.table;
            const container = document.getElementById(`${tableName}-data`);
            fetchAndDisplayData(tableName, container);
        });
    });

    /**
     * Fetches data from the specified API endpoint and displays it in a table.
     * @param {string} tableName - The name of the table to fetch (e.g., 'patients').
     * @param {HTMLElement} container - The container element to display the data in.
     */
    const fetchAndDisplayData = async (tableName, container) => {
        container.innerHTML = 'Loading...';
        try {
            // Assumes your server has an endpoint at /api/admin/{tableName}
            const response = await fetch(`/api/admin/${tableName}`);
            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }
            const data = await response.json();

            if (data.length === 0) {
                container.innerHTML = '<p>No data found.</p>';
                return;
            }

            const table = createTable(data);
            container.innerHTML = '';
            container.appendChild(table);

        } catch (error) {
            console.error('Fetch error:', error);
            container.innerHTML = `<p style="color: red;">Error loading data: ${error.message}</p>`;
        }
    };

    /**
     * Creates an HTML table element from an array of objects.
     * @param {Array<Object>} data - The array of data.
     * @returns {HTMLTableElement} The generated table element.
     */
    const createTable = (data) => {
        const table = document.createElement('table');
        const thead = document.createElement('thead');
        const tbody = document.createElement('tbody');
        const headerRow = document.createElement('tr');

        // Create table headers from the keys of the first object
        const headers = Object.keys(data[0]);
        headers.forEach(headerText => {
            const th = document.createElement('th');
            th.textContent = headerText;
            headerRow.appendChild(th);
        });

        thead.appendChild(headerRow);
        table.appendChild(thead);

        // Create table rows and cells from the data
        data.forEach(rowData => {
            const row = document.createElement('tr');
            headers.forEach(header => {
                const td = document.createElement('td');
                let cellData = rowData[header];
                // Format date/time for better readability
                if (cellData && (header.includes('date') || header.includes('updated'))) {
                    cellData = new Date(cellData).toLocaleString();
                }
                td.textContent = cellData;
                row.appendChild(td);
            });
            tbody.appendChild(row);
        });

        table.appendChild(tbody);
        return table;
    };
});
