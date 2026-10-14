# System status: `GET /data/ignite/system/status`

Read tier: any authenticated caller (Bearer session or site API key) with a resolved site
(`X-Ignite-Site` or the key), same as `/tags/read`. No role is needed.

```
200 {
  "gatewayName": "...",
  "uptimeMs": 123456,
  "license": { "mode": "Trial", "trialExpired": false, "trialTimeLeftMs": 6000000 }
}
```

- `license.mode`: `Free`, `Trial`, `Activated` or `Invalid` (the platform licence state).
- `license.trialExpired`: true once the platform trial has lapsed. The historian and alarm
  journal stop while it is true; reset the trial from the gateway home page.
- `license.trialTimeLeftMs`: milliseconds until the trial expires; `null` when not in trial mode.
- `historianAvailable` is not reported: the SDK has no cheap, reliable historian check.

Errors: `401 {"error":"Unauthorized"}` without credentials; `403` when no site resolves.

`GET /system/info` (gateway administrators only) is unchanged.
