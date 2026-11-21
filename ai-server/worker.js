// Pantry Passport AI server (Cloudflare Worker).
// Keeps your Anthropic API key off the phone. The app sends a prompt (+ optional photos),
// this server asks Claude and sends back the text.
// Secrets to set (see README): ANTHROPIC_API_KEY, APP_KEY

const MODELS = { quick: "claude-haiku-5-5", default: "claude-sonnet-5-5", complex: "claude-sonnet-5-5" };
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-App-Key",
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json", ...CORS } });

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    if (req.method !== "POST") return json({ error: "Use POST" }, 405);
    if (!env.APP_KEY || req.headers.get("X-App-Key") !== env.APP_KEY) return json({ error: "Wrong app key" }, 401);

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
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: MODELS[body.tier] || MODELS.default,
        max_tokens: 8000,
        messages: [{ role: "user", content }],
      }),
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) return json({ error: (data.error && data.error.message) || "Claude error" }, r.status === 429 ? 429 : 502);
    const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
    return json({ text, truncated: data.stop_reason === "max_tokens" });
  },
};
