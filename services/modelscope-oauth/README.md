# Nestro ModelScope OAuth service

This service implements the confidential-client side of ModelScope OAuth 2.0 Authorization Code:
1. Nestro opens `GET /oauth/modelscope/start?app_state=<random>`.
2. The service redirects to ModelScope with `openid profile` and a server-registered callback.
3. The server validates one-time `state`, exchanges the code using the server-only client secret, and fetches OIDC userinfo.
4. The server redirects to the Nestro app link with a short-lived, one-time opaque handoff.
5. Nestro calls `POST /oauth/modelscope/complete` with the handoff and original app_state. The server consumes it and returns the access token exactly once.

## Configure once before deployment

- Register a ModelScope OAuth interconnect application and obtain Client ID / Client Secret.
- Register the exact HTTPS value of `MODELSCOPE_REDIRECT_URI` in that application.
- Set `NESTRO_APP_CALLBACK_URI=nestro://oauth/modelscope` and allow only that value in `ALLOWED_APP_CALLBACKS`.
- Deploy behind HTTPS. Set all values through the hosting provider's secret/environment settings; do not commit a real `.env`.
- Configure Android Gradle property `modelscopeOAuthBackendUrl` to the deployed service origin when building Nestro.

The ModelScope OAuth provider currently documents `https://www.modelscope.cn/oauth/authorize`, `/oauth/token`, and `/oauth/userinfo`; scopes `openid profile`.

## Security / operations

- Client secret never enters the Android APK.
- OAuth state and handoff are random, expiring, single-use values. The app state binds the handoff to the initiating app flow.
- Responses are no-store; endpoints are rate-limited; token/profile calls have timeouts.
- The included in-memory TTL store is for a single service instance. For multiple replicas or restart-survivable sessions, replace it with a shared Redis TTL store before production.
- Access token is returned only to the app over HTTPS, then must be persisted by Android Keystore-backed storage. Never log it.
- OAuth login proves account identity and yields an OAuth access token. Confirm ModelScope grants this token the required API-Inference entitlement for the registered app before describing it as equivalent to a manually generated Access Token; if not, keep both credential types distinct.

Run: `npm install && npm test && npm start`.
