// Base API URL
const API_BASE = '/api/v1/admin';

// DOM Elements
const viewDashboard = document.getElementById('view-dashboard');
const viewAccounts = document.getElementById('view-accounts');
const viewAudit = document.getElementById('view-audit');
const navLinks = document.querySelectorAll('.nav-links li');
const pageTitle = document.getElementById('page-title');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    switchView('dashboard');
    setupEventListeners();
    fetchDashboardData();
});

// Navigation
function switchView(viewId) {
    // Update Nav
    navLinks.forEach(link => {
        if (link.dataset.view === viewId) {
            link.classList.add('active');
            pageTitle.innerText = link.innerText.trim() + ' Overview';
        } else {
            link.classList.remove('active');
        }
    });

    // Update Views
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.getElementById(`view-${viewId}`).classList.add('active');

    // Load Data
    if (viewId === 'dashboard') fetchDashboardData();
    if (viewId === 'accounts') fetchAccounts();
    if (viewId === 'audit') fetchAuditLogs();
}

navLinks.forEach(link => {
    link.addEventListener('click', () => switchView(link.dataset.view));
});

// Modals
function openModal(id) {
    document.getElementById(id).classList.add('active');
}

function closeModal(id) {
    document.getElementById(id).classList.remove('active');
}

// Formatters
const formatCurrency = (paise) => `₹${(paise / 100).toFixed(2)}`;
const formatDate = (isoString) => new Date(isoString).toLocaleString();

// Toast Notifications
function showToast(message, type = 'success') {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    
    const icon = type === 'success' ? 'checkmark-circle-outline' : 'alert-circle-outline';
    toast.innerHTML = `<ion-icon name="${icon}"></ion-icon> <span>${message}</span>`;
    
    container.appendChild(toast);
    
    setTimeout(() => {
        toast.style.animation = 'fadeOut 0.3s ease forwards';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// API Calls - Dashboard
async function fetchDashboardData() {
    try {
        const [accountsRes, auditRes] = await Promise.all([
            fetch(`${API_BASE}/accounts`),
            fetch(`${API_BASE}/audit`)
        ]);

        const accounts = await accountsRes.json();
        const logs = await auditRes.json();

        // Stats
        let totalPaise = 0;
        accounts.forEach(a => totalPaise += a.balancePaise);
        
        document.getElementById('dash-volume').innerText = formatCurrency(totalPaise);
        document.getElementById('dash-accounts').innerText = accounts.length;
        document.getElementById('dash-txns').innerText = logs.filter(l => l.eventType.startsWith('TXN')).length;

        // Recent Activity
        const tbody = document.querySelector('#dashboard-recent-table tbody');
        tbody.innerHTML = '';
        logs.slice(0, 5).forEach(log => {
            const statusBadge = log.success 
                ? `<span class="badge success">Success</span>`
                : `<span class="badge danger">Failed</span>`;
                
            tbody.innerHTML += `
                <tr>
                    <td>${formatDate(log.timestamp)}</td>
                    <td><strong>${log.eventType}</strong></td>
                    <td>${log.sourceAccount || '-'}</td>
                    <td>${statusBadge}</td>
                </tr>
            `;
        });
    } catch (e) {
        showToast('Failed to load dashboard data', 'error');
    }
}

// API Calls - Accounts
async function fetchAccounts() {
    try {
        const res = await fetch(`${API_BASE}/accounts`);
        const accounts = await res.json();
        
        const tbody = document.querySelector('#accounts-table tbody');
        tbody.innerHTML = '';
        
        accounts.forEach(acc => {
            const status = acc.active 
                ? `<span class="badge success">Active</span>`
                : `<span class="badge danger">Inactive</span>`;
                
            tbody.innerHTML += `
                <tr>
                    <td><strong>${acc.accountNumber}</strong></td>
                    <td>${acc.name}</td>
                    <td>${acc.phoneNumber}</td>
                    <td style="font-weight:600; color:var(--primary)">${formatCurrency(acc.balancePaise)}</td>
                    <td>${status}</td>
                    <td>
                        <button class="btn btn-sm btn-secondary btn-icon" onclick="openEditModal('${acc.accountNumber}', ${acc.balancePaise})" title="Edit Balance">
                            <ion-icon name="create-outline"></ion-icon>
                        </button>
                        <button class="btn btn-sm btn-danger btn-icon" onclick="deleteAccount('${acc.accountNumber}')" title="Delete">
                            <ion-icon name="trash-outline"></ion-icon>
                        </button>
                    </td>
                </tr>
            `;
        });
    } catch (e) {
        showToast('Failed to load accounts', 'error');
    }
}

// Create Account
document.getElementById('createAccountForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
        accountNumber: document.getElementById('new-acc-no').value,
        name: document.getElementById('new-acc-name').value,
        phoneNumber: document.getElementById('new-acc-phone').value,
        balancePaise: parseInt(document.getElementById('new-acc-balance').value)
    };

    try {
        const res = await fetch(`${API_BASE}/accounts`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            showToast('Account created successfully');
            closeModal('createAccountModal');
            document.getElementById('createAccountForm').reset();
            fetchAccounts();
        } else {
            const err = await res.json();
            showToast(err.error || 'Failed to create account', 'error');
        }
    } catch (e) {
        showToast('Network error', 'error');
    }
});

// Edit Balance
function openEditModal(accountNo, currentBalance) {
    document.getElementById('edit-acc-no').value = accountNo;
    document.getElementById('edit-acc-display').value = accountNo;
    document.getElementById('edit-acc-balance').value = currentBalance;
    openModal('editBalanceModal');
}

document.getElementById('editBalanceForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const account = document.getElementById('edit-acc-no').value;
    const balancePaise = parseInt(document.getElementById('edit-acc-balance').value);

    try {
        const res = await fetch(`${API_BASE}/accounts/${account}/balance`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ balancePaise })
        });

        if (res.ok) {
            showToast('Balance updated successfully');
            closeModal('editBalanceModal');
            fetchAccounts();
        } else {
            showToast('Failed to update balance', 'error');
        }
    } catch (e) {
        showToast('Network error', 'error');
    }
});

// Delete Account
async function deleteAccount(account) {
    if (!confirm(`Are you sure you want to delete account ${account}? This cannot be undone.`)) return;

    try {
        const res = await fetch(`${API_BASE}/accounts/${account}`, { method: 'DELETE' });
        if (res.ok) {
            showToast('Account deleted');
            fetchAccounts();
        } else {
            showToast('Failed to delete account', 'error');
        }
    } catch (e) {
        showToast('Network error', 'error');
    }
}

// API Calls - Audit Logs
async function fetchAuditLogs() {
    try {
        const res = await fetch(`${API_BASE}/audit`);
        const logs = await res.json();
        
        const tbody = document.querySelector('#audit-table tbody');
        tbody.innerHTML = '';
        
        logs.forEach(log => {
            const status = log.success 
                ? `<span class="badge success">Success</span>`
                : `<span class="badge danger">Failed</span>`;
                
            let badgeClass = 'info';
            if(log.eventType.includes('FAIL')) badgeClass = 'danger';
            if(log.eventType.includes('SUCCESS')) badgeClass = 'success';

            tbody.innerHTML += `
                <tr>
                    <td>${formatDate(log.timestamp)}</td>
                    <td><span class="badge ${badgeClass}">${log.eventType}</span></td>
                    <td>${log.sourceAccount || '-'}</td>
                    <td>${log.sourcePhone || '-'}</td>
                    <td style="max-width:300px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${log.detail}">
                        ${log.detail}
                    </td>
                    <td>${status}</td>
                </tr>
            `;
        });
    } catch (e) {
        showToast('Failed to load audit logs', 'error');
    }
}

// Seed Data
document.getElementById('btn-seed').addEventListener('click', async () => {
    try {
        const res = await fetch(`${API_BASE}/seed`, { method: 'POST' });
        const data = await res.json();
        if (data.status === 'already_seeded') {
            showToast('Database already seeded', 'info');
        } else {
            showToast('Test data seeded successfully');
            fetchDashboardData();
            if (document.getElementById('view-accounts').classList.contains('active')) fetchAccounts();
        }
    } catch (e) {
        showToast('Network error', 'error');
    }
});
