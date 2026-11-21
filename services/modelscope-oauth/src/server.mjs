import crypto from "node:crypto";
import express from "express";
import helmet from "helmet";
import { rateLimit } from "express-rate-limit";

const app = express();
app.disable("x-powered-by");
app.use(helmet({ contentSecurityPolicy: false }));
app.use(express.json({ limit: "8kb", strict: true }));

const {
  PORT = "8080",
  PUBLIC_BASE_URL,
  MODELSCOPE_CLIENT_ID,
  MODELSCOPE_CLIENT_SECRET,
  MODELSCOPE_REDIRECT_URI,
  NESTRO_APP_CALLBACK_URI = "nestro://oauth/modelscope",
  ALLOWED_APP_CALLBACKS = "nestro://oauth/modelscope",
  SESSION_TTL_SECONDS = "300"
} = process.env;
const required = ["PUBLIC_BASE_URL", "MODELSCOPE_CLIENT_ID", "MODELSCOPE_CLIENT_SECRET", "MODELSCOPE_REDIRECT_URI"];
const missing = required.filter((key) => !process.env[key]);
if (missing.length) {
  console.error(`Missing required environment variables: ${missing.join(", ")}`);
  if (process.env.NODE_ENV === "production") process.exit(1);
}
const publicBase = PUBLIC_BASE_URL ? new URL(PUBLIC_BASE_URL) : null;
if (publicBase && publicBase.protocol !== "https:") throw new Error("PUBLIC_BASE_URL must use HTTPS");
const redirectUri = MODELSCOPE_REDIRECT_URI || "https://localhost/oauth/modelscope/callback";
const callbackAllowlist = new Set(ALLOWED_APP_CALLBACKS.split(",").map((v) => v.trim()).filter(Boolean));
const ttlMs = Math.max(60, Math.min(Number(SESSION_TTL_SECONDS) || 300, 600)) * 1000;
const states = new Map();
const handoffs = new Map();
const now = () => Date.now();
const random = () => crypto.randomBytes(32).toString("base64url");
const cleanup = (store) => {
  for (const [key, value] of store) if (value.expiresAt <= now()) store.delete(key);
};
const noStore = (_req, res, next) => {
  res.set("Cache-Control", "no-store");
  res.set("Pragma", "no-cache");
  next();
};
app.use(noStore);
app.use("/oauth/modelscope", rateLimit({ windowMs: 60_000, limit: 30, standardHeaders: true, legacyHeaders: false }));
app.get("/health", (_req, res) => res.json({ status: "ok", provider: "modelscope" }));

app.get("/oauth/modelscope/start", (req, res) => {
  cleanup(states);
  const appState = String(req.query.app_state || "");
  const appCallback = String(req.query.app_callback || NESTRO_APP_CALLBACK_URI);
  if (!/^[A-Za-z0-9_-]{20,128}$/.test(appState)) return res.status(400).json({ error: "invalid_app_state" });
  if (!callbackAllowlist.has(appCallback)) return res.status(400).json({ error: "callback_not_allowed" });
  const state = random();
  states.set(state, { appState, appCallback, expiresAt: now() + ttlMs });
  const authorize = new URL("https://www.modelscope.cn/oauth/authorize");
  authorize.search = new URLSearchParams({
    client_id: MODELSCOPE_CLIENT_ID || "not-configured",
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid profile",
    state
  }).toString();
  return res.redirect(302, authorize.toString());
});

app.get("/oauth/modelscope/callback", async (req, res) => {
  cleanup(states);
  const state = String(req.query.state || "");
  const code = String(req.query.code || "");
  const stateRecord = states.get(state);
  states.delete(state);
  if (!stateRecord || !code || code.length > 4096) {
    return res.status(400).send("OAuth state is invalid or expired. Return to Nestro and retry.");
  }
  if (req.query.error) return res.status(400).send("ModelScope authorization was not approved. Return to Nestro and retry.");
  try {
    const tokenResponse = await fetch("https://www.modelscope.cn/oauth/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: MODELSCOPE_CLIENT_ID,
        client_secret: MODELSCOPE_CLIENT_SECRET,
        code,
        redirect_uri: redirectUri
      }),
      signal: AbortSignal.timeout(15_000)
    });
    const tokenBody = await tokenResponse.json().catch(() => ({}));
    if (!tokenResponse.ok || typeof tokenBody.access_token !== "string" || !tokenBody.access_token) {
      return res.status(502).send("ModelScope token exchange failed. Return to Nestro and retry.");
    }
    const profileResponse = await fetch("https://www.modelscope.cn/oauth/userinfo", {
      headers: { Authorization: `Bearer ${tokenBody.access_token}`, Accept: "application/json" },
      signal: AbortSignal.timeout(15_000)
    });
    const profile = await profileResponse.json().catch(() => ({}));
    if (!profileResponse.ok || !(profile.sub || profile.preferred_username)) {
      return res.status(502).send("ModelScope account verification failed. Return to Nestro and retry.");
    }
    cleanup(handoffs);
    const handoff = random();
    handoffs.set(handoff, {
      appState: stateRecord.appState,
      accessToken: tokenBody.access_token,
      expiresAt: now() + ttlMs,
      profile: {
        subject: String(profile.sub || ""),
        username: String(profile.preferred_username || ""),
        displayName: String(profile.name || profile.preferred_username || "ModelScope")
      }
    });
    const callback = new URL(stateRecord.appCallback);
    callback.searchParams.set("handoff", handoff);
    callback.searchParams.set("app_state", stateRecord.appState);
    return res.redirect(303, callback.toString());
  } catch {
    return res.status(502).send("ModelScope authorization service is temporarily unavailable.");
  }
});

app.post("/oauth/modelscope/complete", (req, res) => {
  cleanup(handoffs);
  const handoff = String(req.body?.handoff || "");
  const appState = String(req.body?.app_state || "");
  const record = handoffs.get(handoff);
  const expected = Buffer.from(record?.appState || "");
  const received = Buffer.from(appState);
  if (!record || expected.length !== received.length || !crypto.timingSafeEqual(expected, received)) {
    return res.status(401).json({ error: "handoff_invalid_or_expired" });
  }
  handoffs.delete(handoff);
  return res.json({ access_token: record.accessToken, token_type: "Bearer", account: record.profile });
});
app.use((_req, res) => res.status(404).json({ error: "not_found" }));
app.listen(Number(PORT), "0.0.0.0", () => console.log(`Nestro ModelScope OAuth listening on ${PORT}`));
