# Wacha Phone Assistant

Private Android assistant for controlled WhatsApp messaging, SMS awareness, personal finance reporting, and local communication intelligence. AI call answering remains intentionally deferred.

## Stage 1 — controlled WhatsApp assistant

- Captures incoming WhatsApp and WhatsApp Business notification text after the phone owner grants Android Notification Access.
- Stores captured messages locally in encrypted app-private storage.
- Generates AI reply suggestions on demand.
- Sends replies through WhatsApp's notification `RemoteInput` action where available.
- Uses Android's public `NotificationListenerService` and `RemoteInput` APIs. It does not read WhatsApp's private database, use Accessibility automation, or bypass WhatsApp security.

## Stage 2 — SMS and finance reporting

- Captures new notifications from the device's default SMS app without requesting direct historical SMS database access.
- Separates trusted financial senders from unverified financial-looking SMS.
- Parses Uganda-oriented transaction messages into receipts, payments, withdrawals, airtime in/out, savings movements, loans, loan repayments, interest earned, interest paid/charged, fees, deductions, reversals, and detected closing balances.
- Generates an organised daily financial report using Africa/Kampala dates.
- Supports a secure HTTPS report relay with timestamped HMAC-SHA256 request signing.
- Raw WhatsApp/SMS text stays encrypted on the phone; only the finished financial report is eligible for relay delivery.

## Stage 3 — intelligence layer

- Adds a local **Communication Intelligence** brief for today's WhatsApp/SMS activity: message volume, distinct senders, priority wording, financial alerts, and most-active senders.
- Adds persistent per-chat reply profiles: Natural, Brief, Warm, Professional, and Business.
- Stores optional encrypted per-contact guidance such as relationship/context or desired communication style.
- Applies the same profile to manual AI drafts and opt-in auto-replies.
- Auto-answer remains disabled by default and must be enabled per chat.
- Adds 7-day and month-to-date finance summaries.
- Adds deterministic anomaly detection for unusually large outgoing transactions, high fee ratios, repeated deductions, unverified finance senders, and daily outflow spikes against recent history.
- Retains more encrypted notification history locally so trend baselines can develop over time.

## AI configuration

The personal pilot can use an OpenAI API key entered on-device. The key is encrypted with an AES/GCM key held in Android Keystore and is never committed to source control. AI requests use the Responses API with `store: false` and `gpt-5.6-luna` for short, cost-sensitive reply drafting.

Before a commercial release, API access should move behind a Wacha-controlled server so customer API credentials never live on client devices.

## Build

The repository includes GitHub Actions CI. On each push to `main`, CI installs Android SDK Platform 37.1, runs unit tests, builds the debug APK, and uploads it as the `wacha-phone-assistant-debug` workflow artifact. The app compiles against API 37.1 while keeping its runtime/Play target at API 36.

Local requirements:

- Android Studio Quail 3 or newer
- JDK 17+
- Android SDK Platform 37.1
- Gradle 9.5+

## Safety boundary

- Auto-answer is **off by default** and can only be enabled for an individual WhatsApp chat by the phone owner.
- OTPs, passwords, account-security messages, payments/banking, legal matters, and medical matters remain sensitive and are blocked from autonomous sending by the local risk classifier.
- Per-contact guidance is treated only as a tone/context preference and cannot override the fixed safety instructions sent to the AI model.
- Communication summaries remain local to the handset. The email relay is reserved for the financial report.
- Finance trends and anomaly warnings are bookkeeping aids derived from captured notification text, not bank or mobile-money statements.
