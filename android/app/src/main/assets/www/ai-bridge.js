// Pantry Passport (iPhone): connects the app's AI features to your own AI server.
// Leave AI_URL empty to run without AI (built-in recipe book + My recipes still work).
// The server only answers requests coming from the app's own pages.
const AI_URL = "https://pantry-passport-ai.codywang1.workers.dev";

(function(){
  if (!AI_URL) return; // no AI: the app falls back to the recipe book

  const blobToB64 = b => new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(",")[1]);
    r.onerror = rej; r.readAsDataURL(b);
  });

  async function sample(input, opts = {}) {
    const prompt = typeof input === "string" ? input : input.map(m => m.content).join("\n\n");
    let imgs = opts.images ? Array.from(opts.images.length !== undefined ? opts.images : [opts.images]) : [];
    const images = [];
    for (const b of imgs.slice(0, 4)) images.push({ media_type: b.type || "image/jpeg", data: await blobToB64(b) });
    let resp;
    try {
      resp = await fetch(AI_URL, {
        method: "POST", signal: opts.signal,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt, images, tier: opts.modelTier || "default" })
      });
    } catch (e) {
      throw { code: e && e.name === "AbortError" ? "cancelled" : "upstream_error", message: "Network error" };
    }
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok) throw { code: resp.status === 429 ? "rate_limited" : resp.status === 401 ? "not_granted" : "upstream_error", message: body.error || "AI server error" };
    if (!body.text) throw { code: "empty_completion", message: "No answer" };
    return { text: body.text, truncated: !!body.truncated };
  }
  sample.json = async (input, opts) => {
    const { text } = await sample(input, opts);
    const tries = [text];
    const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/); if (fence) tries.push(fence[1]);
    const a = text.search(/[\[{]/), z = Math.max(text.lastIndexOf("}"), text.lastIndexOf("]"));
    if (a > -1 && z > a) tries.push(text.slice(a, z + 1));
    for (const t of tries) { try { return JSON.parse(t); } catch (e) {} }
    throw { code: "invalid_json", message: "Could not read the answer", text };
  };
  sample.limits = async () => ({ maxPromptBytes: 262144, images: { maxCount: 4, maxInputBytes: 20000000, mediaTypes: ["image/jpeg", "image/png"] } });

  window.claude = { use: async name => (name === "sample" ? sample : null) };
})();
