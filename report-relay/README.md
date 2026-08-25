# Wacha Phone Assistant — Report Relay

This is the isolated email relay for the personal daily finance report produced by the Android app.

## Security model

- Raw WhatsApp/SMS data stays on the Android device.
- The phone sends only the rendered daily report.
- Every request is HMAC-SHA256 signed with a timestamp.
- Requests older/newer than five minutes are rejected.
- The relay can send only to the single `REPORT_TO_EMAIL` recipient configured on the server.
- Report bodies are not persisted by this service and must not be logged.
- Secrets belong in Netlify environment variables, never Git.

## Netlify deployment

Deploy this directory (`report-relay`) as the Netlify project root.

Required production environment variables:

- `REPORT_RELAY_TOKEN` — random secret of at least 32 characters. The same value is saved in the Android app's encrypted report settings.
- `REPORT_TO_EMAIL` — the only permitted destination address.
- `RESEND_API_KEY` — server-side Resend API key.

Optional:

- `REPORT_FROM_EMAIL` — verified sender, e.g. `Wacha Phone Assistant <reports@wachaai.com>`. If omitted, the relay uses `Wacha Phone Assistant <onboarding@resend.dev>` for initial provider testing.

Endpoints:

- `GET /api/health` — returns service/configuration state without exposing secrets.
- `POST /api/report` — HMAC-authenticated report delivery endpoint.

The Android app defaults to a 19:00 Africa/Kampala daily report schedule and uses WorkManager so a missed network window can be retried later.
