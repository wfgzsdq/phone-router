# Third-party notices

## SoftApHelper

- Project: https://github.com/XhyEax/SoftApHelper
- Revision: `a16231df89531d094d0f9e0e73d0259807c4cee0`
- Copyright (c) 2026 XhyEax
- License: MIT; the complete upstream notice is included in
  `app/src/main/assets/licenses/SoftApHelper-LICENSE.txt` and in the APK.
- Sources adapted: `MainHook.java` (fixed-IP section), `ReflectUtils.java`,
  `StackUtils.java`, and the recommended Xposed scope list.
- Local implementation: `SoftApHook.java` and `HookSupport.java` under
  `app/src/main/java/com/phonerouter/hotspot/xposed/`.
- Changes: only Wi-Fi fixed-IP hooks are included; Android 10 is this app's
  minimum version; method overloads are located by name and return type;
  inherited fields/methods are supported; unknown interface types are skipped;
  unrecognized conflict-check interfaces and reflection failures fall back to
  the system implementation. The upstream Android 15+ missing-coordinator
  fallback is retained and logged. No channel, bandwidth, metered flag, USB,
  Bluetooth, P2P, or Ethernet modifications are included.

## Xposed API

The Xposed API 82 is a compile-only dependency resolved from
https://api.xposed.info/. Runtime implementations are supplied by LSPosed;
API stub classes are not bundled into this application's APK.

## QRCode.js

- Project: https://github.com/davidshimjs/qrcodejs
- Revision checked: `04f46c6a0708418cb7b96fc563eacae0fbf77674`
- Copyright (c) 2012 davidshimjs
- License: MIT, included in `app/src/main/assets/licenses/QRCodejs-LICENSE.txt`.
- The bundled `app/src/main/assets/web/qrcode.min.js` matches the upstream file
  at this revision (SHA-256
  `c541ef06327885a8415bca8df6071e14189b4855336def4f36db54bde8484f36`).

## Other dependencies

Gradle resolves AndroidX, Material Components, Kotlin, NanoHTTPD, and test
dependencies from the repositories declared in `settings.gradle.kts`. Their
upstream licenses continue to apply. Gradle wrapper files retain their embedded
Apache-2.0 notices. The project's root MIT license does not replace any
third-party license.
