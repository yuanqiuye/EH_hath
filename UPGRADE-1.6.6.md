# H@H 1.6.6 merge notes

Upgraded the custom 1.6.3 source using the official 1.6.3 source as the three-way merge base.

Official 1.6.6 source: https://upld.e-hentai.org/hath/HentaiAtHome_1.6.6_src.zip

SHA-256: `5b27e3002e1de8e1d368a294d24a0b69cf3dc1cbc9721c65c92b750e65281a87` (matches the official download page).

## Upstream behavior retained

- RPC v16 response signatures and gallery metadata signatures.
- Gallery filename/type validation, directory traversal protection and HTTPS download URLs.
- TLS 1.3/1.2, inline cache size/hash verification and the new background cache pruner.
- Updated startup error handling and disk space diagnostics.

Seven source files required manual conflict resolution: HTTPResponse, HTTPServer, HTTPSession, Settings, HentaiAtHomeClient, GalleryDownloader and ProxyFileDownloader. Updated APIs, security checks and cache-pruner ownership follow 1.6.6.

## Custom behavior retained and repaired

- Prometheus exporter on port 9500, including transfer bytes, overload time and the existing `eh_hath_staic_range_count` spelling for dashboard compatibility.
- Independent upload and speedtest limits. Zero/unset custom values fall back to the server throttle; negative values are rejected.
- The 280-connection file-request guard now returns HTTP 503 instead of throwing an Error past session completion.
- Cache filesystem metadata lookup has an eight-second deadline, with eight isolated workers and a bounded queue. Timed-out work cannot mutate a finished HTTP response. This replaces the uncancelled common-pool request task; RPC and proxy operations retain their own upstream timeouts.
- A cache file that is empty, truncated or stops advancing now fails promptly instead of spinning at EOF. Upstream inline hash verification remains enabled.
- Gallery ZIP creation uses a unique temporary file, propagates copy errors, rejects non-regular files, closes streams and publishes the completed ZIP before deleting sources. A failure during ZIP creation preserves the source directory and any previous ZIP. If deletion fails after publication, the completed ZIP and remaining sources are retained.
- Metrics labels escape quotes, backslashes and newlines; metrics responses use `no-store`. The metrics listener is closed on shutdown.
- Docker uses Temurin 21 LTS. The startup script supports existing login files and quoted optional settings. Build scripts fail on compile errors. Custom sources require Java 11 or newer; Docker supplies Java 21.

## Validation

Run `./test.sh` with JDK 11 or newer. The 33 checks exercise complete/truncated/empty cache reads, metadata timeout/recovery, successful ZIP contents, ZIP failure preservation, symbolic-link rejection, metrics compatibility/escaping, independent throttle defaults, overload handling and valid/tampered RPC responses. Network tests bind only to loopback.

Build the deployment image with `docker build --platform linux/amd64 -t hath-metric:1.6.6 .`.

A real gallery download and long-term load behavior still depend on the upstream service and production traffic; local tests do not simulate the entire H@H network.

## Deployment

Use `portainer-git.compose.yml` as a generic Portainer Editor example. It installs Git only in the build container, fetches the requested repository ref and compiles the client. The final runtime image does not contain Git. Docker Compose 2.17 or newer is required for `dockerfile_inline`.

Set `HATH_GIT_REF` to a branch, tag or commit. Configure credentials, listening ports, labels, transfer limits and storage locations privately in the deployment environment. Do not commit credentials, exported stack configurations, machine-specific paths or operational backup locations.

Before upgrading, keep a private backup and a usable previous image. Allow at least 60 seconds for graceful shutdown. Do not run two clients with the same client ID or listening port.
