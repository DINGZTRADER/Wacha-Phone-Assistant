import { createHmac, timingSafeEqual } from "node:crypto";

const MAX_BODY_BYTES = 128 * 1024;
const MAX_REPORT_CHARS = 100_000;
const MAX_CLOCK_SKEW_SECONDS = 5 * 60;
const RESEND_ENDPOINT = "https://api.resend.com/emails";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function env(name: string): string {
  return (Netlify.env.get(name) ?? "").trim();
}

function constantTimeHexEqual(expected: string, actual: string): boolean {
  if (!/^[a-f0-9]{64}$/i.test(actual)) return false;
  const a = Buffer.from(expected, "hex");
  const b = Buffer.from(actual, "hex");
  return a.length === b.length && timingSafeEqual(a, b);
}

function validEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) && value.length <= 254;
}

export default async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const relayToken = env("REPORT_RELAY_TOKEN");
  const allowedRecipient = env("REPORT_TO_EMAIL").toLowerCase();
  const resendApiKey = env("RESEND_API_KEY");
  const from = env("REPORT_FROM_EMAIL") || "Wacha Phone Assistant <onboarding@resend.dev>";

  if (!relayToken || !validEmail(allowedRecipient) || !resendApiKey) {
    return json({ error: "Relay is not fully configured" }, 503);
  }

  const declaredLength = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    return json({ error: "Request too large" }, 413);
  }

  const timestampText = req.headers.get("x-wacha-timestamp")?.trim() ?? "";
  const signature = req.headers.get("x-wacha-signature")?.trim() ?? "";
  const timestamp = Number(timestampText);
  const nowSeconds = Math.floor(Date.now() / 1000);

  if (!Number.isInteger(timestamp) || Math.abs(nowSeconds - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
    return json({ error: "Invalid or expired request timestamp" }, 401);
  }

  const raw = await req.text();
  if (Buffer.byteLength(raw, "utf8") > MAX_BODY_BYTES) return json({ error: "Request too large" }, 413);

  const expectedSignature = createHmac("sha256", relayToken)
    .update(`${timestampText}.${raw}`, "utf8")
    .digest("hex");

  if (!constantTimeHexEqual(expectedSignature, signature)) {
    return json({ error: "Invalid signature" }, 401);
  }

  let payload: { to?: unknown; subject?: unknown; text?: unknown };
  try {
    payload = JSON.parse(raw) as typeof payload;
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }

  const requestedRecipient = String(payload.to ?? "").trim().toLowerCase();
  const subject = String(payload.subject ?? "").replace(/[\r\n]+/g, " ").trim().slice(0, 160);
  const text = String(payload.text ?? "").trim();

  if (requestedRecipient !== allowedRecipient) return json({ error: "Recipient not allowed" }, 403);
  if (!subject || !text) return json({ error: "Subject and report text are required" }, 400);
  if (text.length > MAX_REPORT_CHARS) return json({ error: "Report too large" }, 413);

  const resendResponse = await fetch(RESEND_ENDPOINT, {
    method: "POST",
    headers: {
      authorization: `Bearer ${resendApiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      from,
      to: [allowedRecipient],
      subject,
      text,
      headers: {
        "X-Wacha-Report": "daily-finance-v1",
      },
    }),
  });

  if (!resendResponse.ok) {
    const status = resendResponse.status >= 500 || resendResponse.status === 429 ? 503 : 502;
    return json({ error: "Email provider rejected the report", providerStatus: resendResponse.status }, status);
  }

  const result = (await resendResponse.json().catch(() => ({}))) as { id?: string };
  return json({ ok: true, id: result.id ?? null });
};

export const config = {
  path: "/api/report",
};
