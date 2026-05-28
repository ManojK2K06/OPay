# Product Requirements Document (PRD)
**Project Name:** OPay - Offline SMS Payment System
**Date:** May 2026

## 1. Introduction
### 1.1 Purpose
The purpose of OPay is to provide a reliable, seamless, and secure digital payment experience for users in areas with zero or limited internet connectivity. By leveraging standard cellular SMS networks as the primary data transport layer, OPay ensures financial inclusion and continuous operation during internet outages.

### 1.2 Target Audience
* Users in remote or rural areas with poor 4G/5G infrastructure.
* Individuals traveling in internet dead-zones.
* Users requiring a highly resilient backup payment method during network outages.

## 2. Product Features & Requirements
### 2.1 Core Functionality
*   **Offline Fund Transfers:** Users must be able to initiate peer-to-peer (P2P) transfers completely offline. The system must format the transaction data and seamlessly open the iOS SMS composer to dispatch the payload.
*   **Offline Balance Enquiry:** Users must be able to check their account balances via SMS.
*   **Dynamic Background Polling:** If the iOS device recovers internet access during an SMS transaction, the app must automatically poll the server via HTTP to provide instant UI feedback without waiting for an SMS reply.
*   **Immutable Transaction History:** All initiated transactions must be permanently logged on the local device, even if the SMS fails to send, ensuring a verifiable audit trail.

### 2.2 Security & Authentication
*   **Biometric Security (FaceID/PIN):** The application must lock itself when sent to the background and require biometric authentication or a device passcode upon reopening.
*   **Data Integrity:** Offline queued transactions must use cryptographic hashing to prevent local tampering before dispatch.

### 2.3 System Architecture Components
*   **iOS Client:** The user-facing Swift application handling UI, local queuing, and SMS composition.
*   **Android Gateway:** A bridge device running Termux that receives SMS messages from the iOS client and relays them to the backend server via HTTP webhooks.
*   **Java Backend Server:** A Spring Boot application responsible for transaction validation, account balance management, and generating SMS responses for the Android Gateway to send back to the user.

## 3. User Flow
1. **Onboarding:** User launches the app, enters their account number, and is authenticated.
2. **Dashboard:** User views their current balance and a list of locally logged transactions.
3. **Transaction Initiation:** User taps "Transfer", inputs a destination account and amount. The app prepares an `OPAY-TXN:` payload and opens the SMS composer.
4. **Execution:** User hits "Send" in the Messages app. The app logs the transaction to the local CoreData database as "dispatched".
5. **Gateway Relay:** The Android Gateway intercepts the SMS and forwards it to the Java Server.
6. **Confirmation:** The Java Server updates the database and sends a success SMS back through the Android Gateway, or the iOS app polls the server (if online) and instantly reflects the new balance.

## 4. Non-Functional Requirements
*   **Reliability:** The app must never block the user from composing an SMS based on false internet connectivity assumptions.
*   **Usability:** The user interface must be modern, dark-themed, and intuitive, hiding the complexity of the SMS routing from the end-user.
