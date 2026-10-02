# Zqnt Framework Edge SDK
## Authentication

Both directions of an adapter's gRPC traffic are authenticated (package `com.zqnt.sdk.edge.auth`):

- `EdgeCredentialsClientInterceptor` puts the adapter's edge credential (`ZQNT_EDGE_TOKEN`) on every
  call into the platform. Issue one in the console (Edge Credentials,
  `POST /api/admin-console/edge-credentials`) or with `core/scripts/mint-edge-credential.py`. The
  platform refuses calls without it.
- `PlatformAuthServerInterceptor` refuses every command not signed by the platform
  (`ZQNT_PLATFORM_PUBLIC_KEY`, alias `SERVICE_AUTH_PUBLIC_KEY`); without the key it refuses
  everything. `ZQNT_EDGE_AUTH_DISABLED=true` accepts unauthenticated commands (local SITL only).

`EdgeAuthConfig.fromEnv()` reads all three.
