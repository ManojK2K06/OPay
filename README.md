# OPay - Offline SMS-Based Payment System

OPay is an innovative iOS payment application that allows users to send funds and check balances completely offline by routing requests through SMS. It utilizes an Android device running Termux as a bridge (gateway) to connect SMS messages to a backend Java Spring Boot server.

## Architecture

1.  **iOS Client:** Swift/SwiftUI app. Sends offline SMS messages containing transaction payloads or balance queries.
2.  **Android Gateway (Termux):** Acts as the SMS-to-HTTP bridge. A Python script reads incoming SMS messages using the `termux-api`, forwards them to the server via webhooks, and polls the server for outgoing SMS replies to send back to the iOS client.
3.  **Java Backend:** Spring Boot server using an H2 database. Processes webhooks, manages account balances, logs an immutable audit trail, and queues SMS responses.
4.  **Cloudflare Tunnel:** Used to expose the local Java server securely to the internet so the Android Gateway and iOS App (when online) can communicate with it.

---

## Where to Change Configuration (IMPORTANT)

Before running the project or pushing to a public repository, ensure you update the following endpoints and phone numbers:

### 1. iOS App (`ios-client/OPayClient/Networking/SMSGatewayClient.swift`)
*   **`gatewayPhoneNumber`**: Set this to the phone number of your Android Gateway device (e.g., `+919999999999`).
*   **`tunnelURL`**: Update this to your active Cloudflare tunnel URL (e.g., `https://your-tunnel.trycloudflare.com`).

### 2. Android Gateway (`gateway.py`)
*   **`WEBHOOK_URL`**: Update to `https://your-tunnel.trycloudflare.com/api/v1/sms/webhook`
*   **`POLL_URL`**: Update to `https://your-tunnel.trycloudflare.com/api/v1/sms/outgoing`

---

## Full Setup & Run Guide

Follow these exact steps in order to bring the entire system online:

### Step 1: Start the Java Server
Open a terminal on your computer, navigate to the Java server directory, and start Spring Boot:
```bash
cd java-server
mvn spring-boot:run
```
*(The server will start on port `8080` with an H2 database file created in `java-server/data/opaydb.mv.db`)*

### Step 2: Start the Cloudflare Tunnel
In a new terminal window on your computer, expose your local port 8080 to the internet:
```bash
cloudflared tunnel --url http://localhost:8080
```
*Note the `.trycloudflare.com` URL generated in the logs. You will need this for Step 3 and 4.*

### Step 3: Run the Termux Gateway (Android Phone)
1. Ensure the Termux app and `Termux:API` app are installed on the Android phone.
2. Ensure SMS permissions are granted to Termux.
3. Copy the `gateway.py` script to the phone.
4. **Edit `gateway.py`** to replace the old Cloudflare URLs with the new one from Step 2.
5. Run the script:
```bash
python gateway.py
```
*(The script will now monitor for incoming SMS and poll the server every 2 seconds)*

### Step 4: Run the iOS App
1. Open the project in Xcode.
2. **Edit `SMSGatewayClient.swift`** and update the `tunnelURL` and `gatewayPhoneNumber` to match your current setup.
3. Build and run the app on your physical iPhone.

### Step 5: Test the System
1. **Transfer Funds:** Tap Transfer, enter an account number and amount, and hit Send. The iOS app will open the SMS composer. Send the text. The Termux script will detect it, forward it to the server, and send you a success SMS back!
2. **Check Balance:** Tap Check Balance via SMS. Send the text. The Termux script will forward it, and the iOS app will silently poll the Cloudflare tunnel in the background to instantly update your UI.
