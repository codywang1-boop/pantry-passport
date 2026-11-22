// Pantry Passport AI server (Cloudflare Worker).
// Keeps your Anthropic API key off the phone. The app sends a prompt (+ optional photos),
// this server asks Claude and sends back the text.
// Secret to set in Cloudflare: ANTHROPIC_API_KEY. Only the app's own pages may call it.

const MODELS = { quick: "claude-haiku-5-5", default: "claude-sonnet-5-5", complex: "claude-sonnet-5-5" };
// Only these pages may call the server (the web app and the iPhone app).
const ALLOWED = ["https://codywang1-boop.github.io", "capacitor://localhost", "http://localhost"];
const DAILY_LIMIT = 40; // AI calls per visitor per day (best effort)
const seen = new Map();
const CORS = {
  "Access-Control-Allow-Origin": "https://codywang1-boop.github.io",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-App-Key",
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json", ...CORS } });

function authHeaders(env) {
  const k = String(env.ANTHROPIC_API_KEY || "").trim();
  return k.startsWith("sk-ant-api") ? { "x-api-key": k } : { "authorization": "Bearer " + k };
}

export default {
  async fetch(req, env) {
    const origin = req.headers.get("Origin") || "";
    if (ALLOWED.includes(origin)) CORS["Access-Control-Allow-Origin"] = origin;
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    if (!ALLOWED.includes(origin)) return json({ error: "Not allowed" }, 403);
    const ip = req.headers.get("CF-Connecting-IP") || "x";
    const day = new Date().toISOString().slice(0, 10);
    const key = day + ip, n = (seen.get(key) || 0) + 1;
    if (seen.size > 5000) seen.clear();
    seen.set(key, n);
    if (n > DAILY_LIMIT) return json({ error: "Daily limit reached" }, 429);
    if (req.method !== "POST") return json({ error: "Use POST" }, 405);

    let body;
    try { body = await req.json(); } catch { return json({ error: "Bad request" }, 400); }
    const prompt = String(body.prompt || "").slice(0, 60000);
    if (!prompt) return json({ error: "Empty prompt" }, 400);
    const images = Array.isArray(body.images) ? body.images.slice(0, 4) : [];

    const content = [
      ...images.map(i => ({ type: "image", source: { type: "base64", media_type: i.media_type || "image/jpeg", data: i.data } })),
      { type: "text", text: prompt },
    ];

    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        ...authHeaders(env),
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: MODELS[body.tier] || MODELS.default,
        max_tokens: 8000,
        messages: [{ role: "user", content }],
      }),
    });
    const raw = await r.text(); let data = {}; try { data = JSON.parse(raw); } catch {}
    if (!r.ok) return json({ error: (data.error && data.error.message) || ("Claude error " + r.status + " " + raw.slice(0, 200))) }, r.status === 429 ? 429 : 502);
    const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
    return json({ text, truncated: data.stop_reason === "max_tokens" });
  },
};
