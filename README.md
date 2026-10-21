# ExServerNotify

Ultra-lightweight Fabric mod (Minecraft 26.2+) for real-time server lifecycle alerts and live radar coordinates dispatch to [ExServerWeb](https://github.com/Ex-Modding/ExServerWeb).

## Features

- **Lifecycle Webhooks**: Dispatches `server_started` and `server_stopping` events to `POST /api/notify` on [ExServerWeb](https://ex-server-web.vercel.app).
- **Tactical Live Radar**: Asynchronously dispatches online player coordinates `(X, Y, Z, dimension)` to `POST /api/radar` every ~8 seconds when players are online.
- **Zero Overhead**: Consumes 0 MB persistent RAM, uses native non-blocking `java.net.http.HttpClient`, and produces zero network traffic when no players are connected.

## Building

```bash
./gradlew build
```

The compiled mod jar will be in `build/libs/ExServerNotify-1.0.0.jar` (~5 KB).
