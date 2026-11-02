document.addEventListener('DOMContentLoaded', () => {
    const buttons = document.querySelectorAll('button[data-table]');

    // The admin API requires an API key (API_KEY on the server). It is asked for once per
    // browser session and kept in sessionStorage — never hardcoded in this file.
    const KEY_STORAGE = 'admin-api-key';
    const apiKey = () => {
        let key = sessionStorage.getItem(KEY_STORAGE) || '';
        if (!key) {
            key = window.prompt('Admin API key (API_KEY on the server):') || '';
            if (key) sessionStorage.setItem(KEY_STORAGE, key);
        }
        return key;
    };

    buttons.forEach((button) => {
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
            // Relative URL: works wherever the server is hosted (it used to be localhost-only).
            const key = apiKey();
            const response = await fetch(`/api/admin/${tableName}?limit=100`, {
                headers: key ? { 'x-api-key': key } : {},
            });
            if (response.status === 401) {
                sessionStorage.removeItem(KEY_STORAGE);
                throw new Error('Not authorised — check the admin API key');
            }
            if (response.status === 404) {
                throw new Error('This build of the server does not expose that table');
            }
            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }
            const payload = await response.json();
            // The API returns { items, count, limit, offset } and pages at 100 rows.
            const data = Array.isArray(payload) ? payload : payload.items || [];

            if (data.length === 0) {
                container.innerHTML = '<p>No data found.</p>';
                return;
            }

            const table = createTable(data);
            container.innerHTML = '';
            if (payload.count !== undefined && payload.count >= (payload.limit || 100)) {
                const note = document.createElement('p');
                note.className = 'muted';
                note.textContent = `Showing first ${payload.count} rows.`;
                container.appendChild(note);
            }
            container.appendChild(table);
        } catch (error) {
            console.error('Fetch error:', error);
            const p = document.createElement('p');
            p.style.color = 'red';
            p.textContent = `Error loading data: ${error.message}`;
            container.innerHTML = '';
            container.appendChild(p);
        }
    };

    /**
     * Creates an HTML table element from an array of objects.
     * Cell values are written with textContent (not innerHTML), so database content can never
     * inject markup into the admin page.
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
        headers.forEach((headerText) => {
            const th = document.createElement('th');
            th.textContent = headerText;
            headerRow.appendChild(th);
        });

        thead.appendChild(headerRow);
        table.appendChild(thead);

        // Create table rows and cells from the data
        data.forEach((rowData) => {
            const row = document.createElement('tr');
            headers.forEach((header) => {
                const td = document.createElement('td');
                let cellData = rowData[header];
                // Format date/time for better readability
                if (cellData && (header.includes('date') || header.includes('updated'))) {
                    const parsed = new Date(cellData);
                    if (!Number.isNaN(parsed.getTime())) cellData = parsed.toLocaleString();
                }
                td.textContent = cellData == null ? '' : cellData;
                row.appendChild(td);
            });
            tbody.appendChild(row);
        });

        table.appendChild(tbody);
        return table;
    };
});
