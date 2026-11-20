# Speedtest-Gegenstelle auf home.winterkind.me (Caddy)

Auf `home.winterkind.me` läuft Caddy als Webserver vor Home Assistant. Der Speedtest kommt
**zusätzlich** dazu, Home Assistant bleibt unverändert.

## Warum ein kleines Backend nötig ist

Die App misst gegen drei Adressen:

| Endpunkt | Methode | Zweck |
|---|---|---|
| `/speedtest/ping` | GET | leere Antwort (204), Latenz und Jitter |
| `/speedtest/download.bin` | GET | große Zufallsdatei, 4 parallele Streams, 10 s |
| `/speedtest/upload` | POST | Daten werden empfangen und verworfen, 4 parallele Streams, 10 s |

Ping und Download könnte Caddy allein liefern, den Upload aber nicht: Caddys `respond`
antwortet sofort, **ohne die hochgeladenen Daten zu lesen**. Der Upload-Wert wäre dann falsch.
Das habe ich mit Caddy 2.6 getestet. Deshalb nimmt ein winziger nginx auf `127.0.0.1:8089` die
Speedtest-Anfragen an, und Caddy leitet nur `/speedtest/*` dorthin weiter. Von außen ist
nginx nicht erreichbar, HTTPS und Zertifikate macht weiter Caddy.

```
Internet ──HTTPS──▶ Caddy :443 ──┬── /speedtest/*  ──▶ nginx 127.0.0.1:8089
                                 └── alles andere  ──▶ Home Assistant (wie bisher)
```

Getestet: Caddy (auch mit `encode gzip`) + dieses Backend, 16-MB-Upload komplett empfangen,
andere Pfade unverändert an das Home-Assistant-Backend weitergeleitet.

## Schutz vor fremder Nutzung

- **Schlüssel:** Die App schickt bei jeder Anfrage den Header `X-Intrepid-Key`. Stimmt er nicht,
  antwortet nginx mit 404, als gäbe es den Speedtest gar nicht. Der Schlüssel steht nicht im
  (öffentlichen) Repo: Er liegt nur in der nginx-Konfiguration auf dem Server und im
  GitHub-Secret `SPEEDTEST_KEY`, aus dem der Build ihn in die App einfügt.
- **Limits pro Client-IP:** höchstens 4 gleichzeitige Verbindungen, Ping max. 2 Anfragen/s
  (Burst 20), Download/Upload max. 10 Anfragen/s (Burst 100). Darüber antwortet nginx mit 429.
  Die echte IP kommt aus dem `X-Forwarded-For`-Header von Caddy.
- Uploads werden pro Anfrage vollständig im Speicher empfangen (max. 17 MB) und dann
  verworfen; auf die Festplatte wird nichts geschrieben.

Lokal getestet (nginx 1.24 hinter Caddy 2.6): ohne/mit falschem Schlüssel 404, mit richtigem
204; der 21. schnelle Ping und die 5. parallele Verbindung bekommen 429; ein 10-MB-Upload mit
2 MB/s wird vollständig empfangen (5 s), bevor die Antwort kommt.

### Schlüssel erzeugen und hinterlegen

1. Auf dem Server einen zufälligen Schlüssel erzeugen und anzeigen:

   ```bash
   openssl rand -hex 24
   ```

2. Diesen Wert auf GitHub als Secret anlegen: Repo → Settings → Secrets and variables →
   Actions → New repository secret, Name `SPEEDTEST_KEY`. Danach muss eine neue App-Version
   gebaut werden, damit der Schlüssel in der App landet.
3. Denselben Wert in der nginx-Konfiguration statt `SCHLUESSEL_HIER_EINTRAGEN` eintragen
   (siehe Schritt 1 unten).

## Schritt 1: Backend einrichten (eine der beiden Varianten)

### Variante A: mit Docker (empfohlen, falls Docker schon läuft)

```bash
sudo mkdir -p /opt/intrepid-speedtest/data/speedtest
cd /opt/intrepid-speedtest
# default.conf und docker-compose.yml aus server/backend/ hierher kopieren,
# in default.conf SCHLUESSEL_HIER_EINTRAGEN durch den eigenen Schlüssel ersetzen
sudo head -c 256M /dev/urandom | sudo tee data/speedtest/download.bin > /dev/null
sudo docker compose up -d
```

### Variante B: ohne Docker, nginx als Paket

```bash
sudo apt install -y nginx
sudo rm -f /etc/nginx/sites-enabled/default     # wichtig: sonst will nginx Port 80, den Caddy belegt
sudo cp speedtest-native.conf /etc/nginx/conf.d/speedtest.conf
sudo sed -i "s/SCHLUESSEL_HIER_EINTRAGEN/DEIN_SCHLUESSEL/" /etc/nginx/conf.d/speedtest.conf
sudo mkdir -p /var/www/speedtest
sudo head -c 256M /dev/urandom | sudo tee /var/www/speedtest/download.bin > /dev/null
sudo nginx -t && sudo systemctl enable --now nginx && sudo systemctl reload nginx
```

Prüfen (auf dem Server):

```bash
curl -sI http://127.0.0.1:8089/speedtest/ping                                    # erwartet: 404
curl -sI -H "X-Intrepid-Key: DEIN_SCHLUESSEL" http://127.0.0.1:8089/speedtest/ping # erwartet: 204
```

## Schritt 2: Caddy erweitern

In `/etc/caddy/Caddyfile` im bestehenden Block für `home.winterkind.me` **oben** diesen
`handle`-Block ergänzen:

```caddy
home.winterkind.me {
	handle /speedtest/* {
		reverse_proxy 127.0.0.1:8089
	}

	# … bestehende Konfiguration für Home Assistant bleibt, wie sie ist …
}
```

Der `handle`-Block sorgt dafür, dass `/speedtest/*` immer zuerst greift, egal welche
Direktiven (`respond`, `reverse_proxy`, `encode` …) sonst im Block stehen.

Hinweis für Docker-Caddy: Läuft Caddy selbst in einem Container, zeigt `127.0.0.1` auf den
Caddy-Container. Dann `host.docker.internal:8089` oder den Namen des Speedtest-Containers in
einem gemeinsamen Docker-Netzwerk verwenden.

Neu laden:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

## Schritt 3: Von außen testen

```bash
K="X-Intrepid-Key: DEIN_SCHLUESSEL"
curl -sI https://home.winterkind.me/speedtest/ping                 # ohne Schlüssel: 404
curl -sI -H "$K" https://home.winterkind.me/speedtest/ping
curl -s -o /dev/null -H "$K" -w '%{speed_download}\n' https://home.winterkind.me/speedtest/download.bin
head -c 16M /dev/urandom | curl -s -o /dev/null -H "$K" -w '%{http_code} %{size_upload}\n' -X POST --data-binary @- https://home.winterkind.me/speedtest/upload
```

Erwartet: `HTTP/2 404`, `HTTP/2 204`, eine Geschwindigkeit in Byte/s und `204 16777216`.

## Hinweise

- **Firewall:** An Port 443 ändert sich nichts, Caddy ist dort schon erreichbar. Port 8089
  muss **nicht** geöffnet werden.
- **Datenvolumen:** Ein Test überträgt je nach Leitung einige hundert MB. Oracle Cloud Free Tier
  enthält 10 TB ausgehenden Traffic pro Monat.
- **Cloudflare oder ein anderer Proxy vor Caddy** würde die Messung verfälschen; der Name sollte
  direkt auf den Server zeigen.
- Server oder Standortangabe in der App ändern: `SpeedtestServer` in
  `app/src/main/java/me/winterkind/multitool/speedtest/SpeedtestEngine.kt`.
