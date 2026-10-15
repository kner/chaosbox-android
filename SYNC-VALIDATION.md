# Validierung der gemeinsamen Synchronisation

Stand: 14. Oktober 2026. Alle Testbestände wurden in temporären Ordnern erzeugt.
Der produktive SSH-Server und die bestehenden Datenbestände wurden nicht verändert.

Erfolgreich geprüft:

- Desktop: 56 Kerntests; 25 Synchronisations-/Transporttests, darunter echte lokale
  rsync-Übertragungen und SSH-Tar-Datenströme mit einem lokalen SSH-Testtransport.
- JSON: Einzelobjekte, Datensatzlisten, gruppierte Objekte, Teil-Datensätze,
  zusätzliche Felder, Unicode, deterministische Altbestands-IDs und idempotente
  Wiederholung. Unabhängige Änderungen derselben JSON-Datei bleiben erhalten.
- Java/Python/PHP: gemeinsamer Austausch, JPEG- und MP4-Metadaten, unveränderte
  kodierte Videostreams, Medienverschiebung, Einzellöschung, Offline-Löschkonflikt,
  explizite Konfliktauflösung, Wiederherstellung und schreibgeschützte Web-Projektion.
- SFTP: tatsächlicher JSch-/Paramiko-Austausch mit einem kurzlebigen Server auf
  127.0.0.1, temporären Schlüsseln und strenger Hostschlüsselprüfung. Geprüft sind
  Unicode, Tombstones, Gerätebestätigungen und Freigabe der Transfersperre.
- Prüfsummenindex: identisches SHA-256-Format auf Java/Python; lokale und
  Server-Prüfsummen stimmen nach erfolgreichem Abgleich überein. Wiederholter
  unveränderter SFTP-Abgleich lädt keine Operationen erneut. Beschädigte/veraltete
  Indizes lösen eine vollständige Prüfung aus; abgebrochene Uploads veröffentlichen
  keinen neuen Serverindex. Geprüft auch mit tatsächlichem JSch/Paramiko-SFTP.
- Integrität: fehlende Vorgänger, zyklische Historien, beschädigte Journale,
  Traversal, lokale Daten ohne Servergegenstück und abgebrochene Transfers.
  Nach einem unterbrochenen Metadaten-Schreibvorgang wird base_revision genutzt;
  bei unbekannter Basis entsteht ein Konflikt statt einer stillen Überschreibung.
- Android: vollständiger APK-Build und Android Lint; JVM-Prüfungen für BoxRecords,
  DataIndex und StoragePaths. Journalzustand und Papierkorb werden nicht indexiert.
- Web: Katalog, Cache, XMP und automatische Journal-Aktualisierung; laufende oder
  unvollständige Transfers ersetzen keinen vorhandenen gültigen Katalog.
  push-data/pull-data wurden mit echtem lokalem rsync getestet.
- Schutz veralteter Auswahlen nach einem Abgleich: Speichern/Löschen erfordert
  erneutes Öffnen; das Leeren einzelner Felder hebt diese Sperre nicht auf.

Die vollständige Desktop-Suite enthält weiterhin die bereits im Ausgangsstand
reproduzierten 2 Fehlschläge und 2 Fehler in älteren GUI-Tests:

- BatchSaveConfirmationTests.test_successful_save_clears_form_for_media_and_json:
  zwei Subtests erzeugen eine unvollständige App-Testinstanz ohne individual_paths.
- UserCommentTests.test_caption_shows_fields_in_order_and_limits_unicode_to_60_characters:
  alte Erwartung zur Vorschautext-Länge.
- UserCommentTests.test_empty_and_multiline_comments:
  alte Erwartung zur Umwandlung von Zeilenumbrüchen in Leerzeichen.

Diese bestehenden GUI-Abweichungen sind keine grünen Tests; sie wurden nicht
im Rahmen der Synchronisationsänderung umdefiniert. Der ursprüngliche Stand
wurde dafür gesondert geladen und geprüft. Die relevanten Kern-, Sync-,
Transport- und Löschtests bestehen.

## Wiederholen

Im Desktop-Projekt:

```sh
/usr/bin/python3 -m unittest discover -s tests -p test_core.py
/usr/bin/python3 -m unittest discover -s tests -p 'test_sync*.py'
```

Im Android-Projekt nach dem Build:

```sh
./build-apk.sh
/usr/bin/python3 tests/test-sync.py --json-jar /path/to/org.json.jar
/usr/bin/python3 tests/test-sftp.py --json-jar /path/to/org.json.jar
```

Im Web-Projekt:

```sh
php tests/ChaosBoxCatalogTest.php
php tests/ChaosBoxCacheTest.php
php tests/ChaosBoxXmpTest.php
php tests/ChaosBoxSyncTest.php
/usr/bin/python3 tests/test_journal_transfer.py
```

Die APK verwendet weiterhin den ursprünglichen Debug-Schlüssel. Es wurde kein
neuer Signaturschlüssel angelegt. Vor dem produktiven Start alle schreibenden
Programme aktualisieren und den vollständigen Altbestand übernehmen; siehe
SYNC-PROTOCOL.md. Die Serverinstallation wurde in dieser Änderung nicht deployed.
