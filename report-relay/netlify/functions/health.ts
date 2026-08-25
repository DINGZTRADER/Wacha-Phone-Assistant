export default async (): Promise<Response> => {
  const configured = Boolean(
    (Netlify.env.get("REPORT_RELAY_TOKEN") ?? "").trim() &&
    (Netlify.env.get("REPORT_TO_EMAIL") ?? "").trim() &&
    (Netlify.env.get("RESEND_API_KEY") ?? "").trim(),
  );

  return new Response(
    JSON.stringify({
      ok: true,
      service: "wacha-phone-assistant-report-relay",
      version: "1.0.0",
      configured,
    }),
    {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      },
    },
  );
};

export const config = {
  path: "/api/health",
};
