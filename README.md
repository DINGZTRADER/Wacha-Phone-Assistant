# Wacha Phone Assistant

Private Android assistant for controlled WhatsApp message handling and, in later stages, AI call screening.

## Stage 1 (v0.1)

- Captures incoming WhatsApp and WhatsApp Business notification text after the phone owner grants Android Notification Access.
- Stores captured messages locally in encrypted app-private storage.
- Generates reply suggestions only when the user explicitly taps **Suggest reply**.
- Sends a reply only after the user explicitly taps **Send reply**.
- Adds a second confirmation for messages involving OTPs, passwords, payments, banking, legal or medical matters.
- Uses Android's public `NotificationListenerService` and `RemoteInput` APIs. It does not read WhatsApp's private database, use Accessibility automation, or bypass WhatsApp security.

## AI configuration

The personal pilot can use an OpenAI API key entered on-device. The key is encrypted with an AES/GCM key held in Android Keystore and is never committed to source control. AI requests use the Responses API with `store: false` and default to `gpt-5.6-luna` for short, cost-sensitive reply drafting.

Before a commercial release, API access will move behind a Wacha-controlled server so customer API credentials never live on client devices.

## Build

The repository includes GitHub Actions CI. On each push to `main`, CI installs Android API 36, runs unit tests, builds the debug APK, and uploads it as the `wacha-phone-assistant-debug` workflow artifact.

Local requirements:

- Android Studio Quail 3 or newer
- JDK 17+
- Android SDK Platform 36
- Gradle 9.5+

## Safety boundary

Stage 1 never autonomously sends replies. Payments, OTPs, account-security actions, legal commitments and similar sensitive matters always require explicit human confirmation.
