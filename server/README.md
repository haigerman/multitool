# Speedtest-Gegenstelle auf home.winterkind.me

Die App misst gegen drei HTTPS-Endpunkte unter `https://home.winterkind.me/speedtest/`:

| Endpunkt | Methode | Zweck |
|---|---|---|
| `/speedtest/ping` | GET | leere Antwort (204), Latenz und Jitter |
| `/speedtest/download.bin` | GET | große Zufallsdatei, 4 parallele Streams, 10 s |
| `/speedtest/upload` | POST | nginx verwirft den Body und antwortet mit 204, 4 parallele Streams, 10 s |

Es wird nur nginx gebraucht, kein PHP und kein eigener Dienst. Die Konfiguration wurde lokal
mit nginx 1.24 getestet.

## Einrichtung (Ubuntu/Oracle Linux mit nginx)

1. Testdatei anlegen (256 MB Zufallsdaten, damit nichts komprimiert oder gecacht werden kann):

   ```bash
   sudo mkdir -p /var/www/speedtest
   sudo head -c 256M /dev/urandom | sudo tee /var/www/speedtest/download.bin > /dev/null
   sudo chmod 644 /var/www/speedtest/download.bin
   ```

2. Snippet installieren:

   ```bash
   sudo cp speedtest.nginx.conf /etc/nginx/snippets/speedtest.conf
   ```

3. Im bestehenden `server { listen 443 ssl; server_name home.winterkind.me; … }`-Block
   folgende Zeile ergänzen:

   ```nginx
   include /etc/nginx/snippets/speedtest.conf;
   ```

   Die `location`-Blöcke darin sind exakte Treffer (`location =`) und haben deshalb Vorrang
   vor einem eventuellen `location /` mit `proxy_pass`.

4. Prüfen und neu laden:

   ```bash
   sudo nginx -t && sudo systemctl reload nginx
   ```

5. Von außen testen:

   ```bash
   curl -sI https://home.winterkind.me/speedtest/ping
   curl -s -o /dev/null -w '%{speed_download}\n' https://home.winterkind.me/speedtest/download.bin
   head -c 50M /dev/zero | curl -s -o /dev/null -w '%{http_code}\n' -X POST --data-binary @- https://home.winterkind.me/speedtest/upload
   ```

   Erwartet: `204`, eine Geschwindigkeit in Byte/s und `204`.

## Oracle Cloud: Firewall

Port 443 muss an zwei Stellen offen sein:

- **VCN → Security List / Network Security Group:** Ingress-Regel TCP 443 von `0.0.0.0/0`.
- **Auf der Instanz** (Oracle-Images haben strenge iptables-Regeln):

  ```bash
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
  sudo netfilter-persistent save
  ```

## Hinweise

- **Datenvolumen:** Ein Test überträgt je nach Leitung einige hundert MB. Oracle Cloud Free Tier
  enthält 10 TB ausgehenden Traffic pro Monat; das reicht für sehr viele Tests.
- **Ein vorgeschalteter Reverse-Proxy oder CDN** (z. B. Cloudflare) würde die Messung verfälschen
  und Uploads begrenzen. `home.winterkind.me` sollte für `/speedtest/` direkt auf nginx zeigen.
- **Missbrauch:** Wer will, kann die Endpunkte mit `limit_req` oder einer IP-Begrenzung
  absichern. Die App braucht keine Authentifizierung.
- Server oder Standortangabe ändern: `SpeedtestServer` in
  `app/src/main/java/me/winterkind/multitool/speedtest/SpeedtestEngine.kt`.
