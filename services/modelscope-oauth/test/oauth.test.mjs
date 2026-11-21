import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("OAuth service keeps provider secret server-side and exposes one-time handoff", async () => {
  const source = await readFile(new URL("../src/server.mjs", import.meta.url), "utf8");
  assert.match(source, /MODELSCOPE_CLIENT_SECRET/);
  assert.match(source, /states\.delete\(state\)/);
  assert.match(source, /handoffs\.delete\(handoff\)/);
  assert.match(source, /timingSafeEqual/);
  assert.match(source, /oauth\/userinfo/);
  assert.doesNotMatch(source, /res\.json\(\{[^}]*client_secret/s);
});

test("deployment template does not contain real credentials", async () => {
  const env = await readFile(new URL("../.env.example", import.meta.url), "utf8");
  assert.match(env, /MODELSCOPE_CLIENT_SECRET=$/m);
  assert.match(env, /MODELSCOPE_REDIRECT_URI=/);
});
