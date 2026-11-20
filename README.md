# Intrepid Multitool

Android-App für Technikinteressierte im LCARS-Design (Star Trek: TNG/Voyager).

| Speedtest | WiFi Scanner | Atomuhr |
|---|---|---|
| ![](app/src/test/snapshots/images/me.winterkind.multitool_ScreenshotTest_speedtest.png) | ![](app/src/test/snapshots/images/me.winterkind.multitool_ScreenshotTest_wifi.png) | ![](app/src/test/snapshots/images/me.winterkind.multitool_ScreenshotTest_clock.png) |

## Funktionen

1. **Speedtest:** Ping, Jitter, Download und Upload gegen den eigenen Server
   `home.winterkind.me` (Oracle Cloud, Niederlande, Caddy). Die Server-Einrichtung steht in
   [server/README.md](server/README.md).
2. **WiFi Scanner:** zeigt alle WLANs in Reichweite mit Kanal, Frequenz, Kanalbreite,
   Verschlüsselung, Wi-Fi-Standard und Signalstärke. Umschaltbar zwischen *Alle*, *2,4 GHz*,
   *5 GHz* und *6 GHz*, mit Kanalbelegungs-Diagramm pro Band.
3. **Atomuhr:** gleicht die Uhr in der Kopfzeile per SNTP mit den Stratum-1-Zeitservern der
   Physikalisch-Technischen Bundesanstalt (`ptbtime1–3.ptb.de`) ab. Angezeigt werden Offset,
   Laufzeit und Genauigkeit.

## Build

- Android Studio oder `./gradlew assembleDebug` (JDK 17+, Android SDK 35)
- minSdk 26 (Android 8.0), targetSdk 35
- Screenshots neu erzeugen: `./gradlew recordPaparazziDebug`

### GitHub Actions

- **Build APK** (`build.yml`): jeder Push baut eine signierte APK und hängt sie als Artefakt an
  den Workflow-Lauf.
- **Release** (`release.yml`): eine Änderung der Datei `VERSION` auf `main` (oder ein manueller
  Start unter Actions → Release) baut die APK und veröffentlicht sie als GitHub Release
  `vX.Y.Z`. Dort findet Obtainium die Updates.

Benötigte Repository-Secrets, gleich wie in den anderen Projekten:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

## Lizenzen

Schrift *Antonio* (Vernon Adams) unter der SIL Open Font License 1.1.
Star Trek und LCARS sind Marken von CBS Studios / Paramount. Dies ist ein privates Fanprojekt
ohne Verbindung zu den Rechteinhabern.
