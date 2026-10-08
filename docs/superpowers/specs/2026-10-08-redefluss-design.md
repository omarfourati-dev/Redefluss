# Redefluss – Design

Stand: 08.10.2026 · Status: abgestimmt mit Omar Fourati, wartet auf Review dieser Datei

## 1. Ziel

Redefluss ist Omars persönlicher Deutsch-Sprechtrainer: **sprechen statt tippen**, nach jedem Satz eine Korrektur
mit Regel und der Formulierung eines Muttersprachlers, ein Gedächtnis für wiederkehrende Fehler, täglich neuer
Wortschatz, Aussprache-Training und realistische Vorstellungsgespräche. Ziel: Deutsch so sprechen, wie ein
Muttersprachler es tut – konkret für Vorstellungsgespräche bei der aktuellen Jobsuche.

Nebenziel: Kotlin (Ktor) und Svelte lernen. Code und README erklären die typischen Konzepte beider Technologien.

Erfolgskriterien:
- Live unter `https://redefluss.omarfourati.de`, als App aufs Handy installierbar (PWA), Mikrofon funktioniert auf
  Android (Chrome) und iPhone (Safari)
- Antwort im Gesprächsmodus in der Regel nach 2–4 Sekunden
- Fehler werden gesammelt, gezählt und in allen Modi gezielt geübt
- Kosten begrenzt (Tageslimits), Aufnahmen werden nie gespeichert
- CI mit Kotlin-, Svelte- und Playwright-Tests, automatischer Deploy, Monitoring mit Alarmen

Nicht im Umfang (YAGNI): mehrere Nutzer, Registrierung, Admin-Seite, Landingpage/SEO (die App ist privat,
`noindex`), andere Sprachen als Deutsch, Push-Benachrichtigungen, Speichern von Audio, Bezahlfunktionen.

## 2. Architektur

Ein Ktor-Programm (ein Container) liefert API und die gebaute Svelte-App aus (Ressourcen im JAR), wie Spring Boot bei
Belegfluss und Go bei Briefklar.

```
Browser (Svelte 5 SPA, PWA) ──HTTPS──► Caddy ──► Ktor (Kotlin, JVM 21)
   │                                              ├─ Auth (JWT), Limits, Metriken
   │                                              ├─ Coach  ──► OpenAI: Transkription, Chat (JSON-Schema), Sprachausgabe
   │                                              ├─ Aussprache ──► Azure Speech (Pronunciation Assessment)
   │                                              ├─ Wortschatz (SM-2), Fehler-Gedächtnis
   │                                              └─ PostgreSQL (Exposed + Flyway)
   └──WebRTC (nur Live-Gespräch)──► OpenAI Realtime   (Ktor gibt nur einen kurzlebigen Schlüssel aus)
```

Bausteine im Backend (je eine klare Aufgabe, über Interfaces austauschbar, in Tests durch Fakes ersetzt):
- `Transcriber` – Audio → wörtlicher Text (OpenAI, Sprache `de`, Prompt „wörtlich, Fehler nicht korrigieren“)
- `Coach` – Satz + Verlauf + bekannte Fehler + Wörter des Tages → strukturierte Korrektur und Antwort
- `Voice` – Text → Audio (OpenAI Sprachausgabe, deutsche Stimme)
- `PronunciationScorer` – WAV + Zielsatz → Punkte pro Wort und Laut (Azure)
- `RealtimeSessions` – kurzlebiger Schlüssel für WebRTC mit Rollen-Anweisungen
- `Scheduler` (SM-2) – reine Funktion, keine Abhängigkeiten
- `Store` – Postgres-Zugriff

Modelle sind per Umgebungsvariable einstellbar (`AI_MODEL`, `TRANSCRIBE_MODEL`, `TTS_MODEL`, `TTS_VOICE`,
`REALTIME_MODEL`); Standard: `gpt-4o-mini`, `gpt-4o-transcribe`, `gpt-4o-mini-tts`, eine deutsche Stimme,
`gpt-realtime`. `SPEECH=fake` schaltet alle externen Dienste auf Fakes (lokal ohne Schlüssel, CI).

## 3. Modi

### 3.1 Gespräch (per Knopf)
- Thema wählen (Arbeit, Alltag, Smalltalk, Nachrichten, Freies Thema) oder ein eigenes eingeben
- Knopf halten → sprechen → loslassen. Der Browser nimmt mit `MediaRecorder` auf (webm/opus bzw. mp4 auf iOS),
  max. 60 s pro Satz
- `POST /api/turns` → Transkription → Coach → Sprachausgabe; Antwort: erkannter Text, Korrekturen, natürliche
  Formulierung, Antwort der KI als Text + Audio
- Anzeige: erkannter Satz mit markierten Fehlern, darunter je Fehler „falsch → richtig – Regel“, dann
  „💬 So klingt es natürlich“, dann die KI-Antwort (Audio spielt automatisch, abschaltbar)
- Alternativ Texteingabe (für leise Umgebungen) – dieselbe Korrektur
- Der Verlauf eines Gesprächs liegt nur im Browser (letzte 20 Runden werden mitgeschickt); gespeichert werden
  Fehler und eine Zusammenfassung der Sitzung

### 3.2 Vorstellungsgespräch (live)
- Stellenanzeige einfügen (Text), Rolle wählen (Recruiter / Teamlead / Mischung), Dauer 10–20 min
- `POST /api/live/session` liefert einen kurzlebigen OpenAI-Schlüssel; Anweisungen: Rolle, Stelle, Omars
  Lebenslauf-Eckdaten (fest im Code, keine Geheimnisse), seine häufigsten Fehler; die KI spricht Hochdeutsch in
  normalem Tempo und korrigiert **während** des Gesprächs nicht
- Der Browser verbindet sich per WebRTC direkt mit OpenAI, zeigt Timer und Live-Untertitel
- Am Ende schickt der Browser das Protokoll (Transkripte beider Seiten) an `POST /api/live/report`
  → Bericht: Sprachfehler (wie 3.1), Inhalt jeder Antwort (Struktur, STAR, Länge, Füllwörter), 3 bessere
  Formulierungen, Gesamteindruck; Fehler gehen ins Gedächtnis
- Kostenbremse: max. 30 Live-Minuten pro Tag (`LIVE_MINUTES_PER_DAY`); die Sitzung endet hart nach der gewählten
  Dauer + 2 min

### 3.3 Aussprache
- Übungssätze aus drei Quellen: eigene Fehler (korrigierte Sätze), Wörter des Tages (Beispielsätze), feste Sätze
  zu schwierigen Lauten (ü/u, ö/o, ch [ç/x], r, Umlaute, Endungen, Satzmelodie bei Fragen)
- „Vorsprechen“: Sprachausgabe des Zielsatzes, auch langsam
- Aufnahme → im Browser in WAV 16 kHz mono umgewandelt (`OfflineAudioContext`) → `POST /api/pronunciation`
  → Azure (Region `germanywestcentral`, Referenztext, Granularität Phonem)
- Anzeige: Gesamtpunkte (Genauigkeit, Flüssigkeit, Vollständigkeit), jedes Wort grün/gelb/rot, Antippen zeigt die
  schwachen Laute; Wörter unter 60 Punkten werden als Aussprache-Fehler gespeichert
- **Nur das kostenlose Kontingent:** Azure-Ressource im Tarif **Free F0** (5 Audio-Stunden pro Monat, danach lehnt
  Azure ab – kein Überlauf in Kosten). Zusätzlich zählt Redefluss die an Azure gesendeten Audio-Sekunden pro
  Kalendermonat und stoppt bei **4,5 Stunden** (`AZURE_SECONDS_PER_MONTH`, Standard 16 200). Die App zeigt das
  Restkontingent; ist es aufgebraucht (oder antwortet Azure mit Kontingent-Fehler), laufen alle anderen Modi weiter,
  Aussprache-Prüfung und Aussprache-Teil der Wortschatz-Wiederholung sind bis zum Monatsersten ausgesetzt

### 3.4 Wortschatz täglich
- Jeden Tag (beim ersten Öffnen) erzeugt der Coach 5–10 neue Wörter (Anzahl einstellbar) aus Omars Themen:
  IT und Bewerbung, Alltag, Redewendungen. Keine Doppelten (bekannte Wörter werden mitgeschickt), Niveau B2–C1
- Karte: Wort, Artikel, Plural (bzw. Konjugation bei Verben), Bedeutung auf Deutsch, Beispielsatz, Audio
- Wiederholung nach SM-2: Karte zeigen → Omar spricht einen Satz mit dem Wort → Coach prüft Verwendung und Grammatik,
  ab Etappe 3 zusätzlich Azure die Aussprache → Bewertung 0–5 → nächster Termin
- Wörter, die Omar im Gespräch falsch verwendet, werden automatisch zu Karten

### 3.5 Fehler-Gedächtnis und Übersicht
- Startseite: Serie in Tagen, heutige Minuten, fällige Karten, „Deine 5 häufigsten Fehler“ mit Beispiel
- Fehler haben eine Kategorie (Artikel/Genus, Kasus, Verbstellung, Konjugation, Groß-/Kleinschreibung, Präposition,
  Wortwahl, Aussprache, Sonstiges), werden über eine normalisierte Form zusammengefasst und gezählt
- Alle Modi bekommen die häufigsten offenen Fehler mit, damit der Coach sie gezielt übt

## 4. KI-Aufrufe

- **Coach (Gespräch)**: Chat mit festem JSON-Schema (strict):
  `{ transcriptMarked, corrections: [{ wrong, right, rule, category }], natural, reply, newVocabulary: [] }`.
  Regeln im System-Prompt: nur echte Fehler korrigieren, Umgangssprache ist erlaubt, wenn sie natürlich ist,
  Antwort 1–3 Sätze und mit einer Rückfrage, damit das Gespräch weiterläuft
- **Bericht (Live)**, **Wörter des Tages**, **Wortschatz-Prüfung**: eigene feste Schemas
- Alle Antworten werden validiert; ungültiges JSON → ein erneuter Versuch, dann 502
- Zeitlimits: Transkription 20 s, Chat 30 s, Sprachausgabe 20 s, Azure 15 s; Antwortgröße begrenzt
- Schlüssel stehen nur in Umgebungsvariablen des Containers, nie im Browser (Ausnahme: der kurzlebige
  Realtime-Schlüssel, der nur eine Minute lang eine Sitzung öffnen darf)

## 5. Login und Limits

- Genau ein Konto aus `OWNER_EMAIL`/`OWNER_PASSWORD` (beim Start angelegt bzw. aktualisiert), Passwort in der App
  änderbar; JWT (HS256, 30 Tage, weil privat und PWA), bcrypt, Login-Drosselung wie Briefklar
- Tageslimits als Kostenbremse (einstellbar): 300 Gesprächsrunden, 30 Live-Minuten, 100 Aussprache-Prüfungen,
  1 Wortschatz-Erzeugung; Überschreitung → 429 mit Hinweis
- Monatslimit Azure: 16 200 Audio-Sekunden (siehe 3.3), gezählt vor dem Aufruf mit der tatsächlichen WAV-Länge

Datenbank (eigene Rolle/DB `redefluss` auf dem vorhandenen Postgres des VPS, Port 5433, wie Briefklar), Flyway:
- `app_user (id, email, password_hash, created_at)`
- `mistake (id, category, wrong, right, rule, example, normalized, count, first_seen, last_seen, resolved)`
- `vocab_card (id, word, article, plural, meaning, example, theme, source, ease, interval_days, reps, due_on, created_on)`
- `practice_session (id, mode, started_at, minutes, summary)`
- `usage_day (day, turns, live_seconds, pronunciations, azure_seconds, vocab_generated)` – Monatssumme von
  `azure_seconds` ergibt das Azure-Kontingent

## 6. Oberfläche (Svelte 5, Runes, SvelteKit als SPA mit `adapter-static`)

- Mobil zuerst, große Sprechtaste, Dunkelmodus, PWA (Manifest, Service Worker nur für das App-Gerüst, nie `/api`)
- Seiten: Login, Übersicht, Gespräch, Vorstellungsgespräch, Aussprache, Wortschatz, Fehler, Konto
- Mikrofon-Berechtigung freundlich erklären; ohne Mikrofon bleibt die Texteingabe
- `robots: noindex`, `robots.txt` sperrt alles

## 7. Fehler

RFC 9457 Problem Details. Kein Mikrofon/keine Sprache erkannt → 422 „Ich habe nichts verstanden – bitte noch einmal“;
Aufnahme zu lang/zu groß → 413; externer Dienst langsam/fehlerhaft → 502/504 mit neutraler Meldung, die Runde lässt
sich wiederholen; Limit → 429; nicht angemeldet → 401. Fehlermeldungen externer Dienste werden nie ungefiltert
ausgegeben (keine Schlüssel in Logs/Antworten). Logs enthalten keine Transkripte.

## 8. Tests

- Kotlin: SM-2 als Tabellentests, Normalisierung/Zusammenfassen von Fehlern, Schema-Validierung der KI-Antworten,
  Limits, JWT, Drosselung; Ktor `testApplication` für alle Routen mit Fakes; OpenAI-/Azure-Clients gegen einen
  lokalen Fake-Server (Ktor MockEngine); Integrationstests mit Testcontainers (Postgres)
- Svelte (Vitest + Testing Library): Sprechtaste/Aufnahme-Zustände, Korrektur-Anzeige, WAV-Umwandlung
  (16 kHz, mono, Header), Karten-Ablauf, PWA-Update
- Playwright gegen den Docker-Stack mit `SPEECH=fake` und Chromes Fake-Mikrofon: Login, Gesprächsrunde mit
  Korrektur, Wortschatz des Tages + Wiederholung, Aussprache-Ergebnis, Live-Gespräch bis zum Bericht (Realtime
  im Fake-Modus ersetzt), installierbar, offline-Hinweis

## 9. Betrieb

- Dockerfile mehrstufig: Node (Svelte) → Gradle (Kotlin, Fat-JAR mit Ressourcen) → Eclipse Temurin JRE 21, nicht-root
- GitHub Actions: `ci.yml` (Build, Tests, E2E) und `deploy.yml` (Self-hosted-Runner wie Briefklar); Secrets:
  `OPENAI_API_KEY` (vorhanden), `AZURE_SPEECH_KEY` (Etappe 3), `JWT_SECRET`, `DB_PASSWORD`, `OWNER_PASSWORD`
  (per Skript gesetzt), Variable `OWNER_EMAIL`
- Caddy: `redefluss.omarfourati.de` mit `import access_log`, `/metrics` öffentlich 404, `request_body` 12 MB
- Kennzahlen: `redefluss_turns_total{outcome}`, `redefluss_stage_duration_seconds{stage}` (transcribe/coach/voice/
  azure), `redefluss_live_seconds_total`, `redefluss_pronunciations_total{outcome}`, `redefluss_mistakes_total{category}`,
  `redefluss_vocab_reviews_total{grade}`, `redefluss_logins_total{outcome}`, `redefluss_azure_seconds_month`
- Monitoring-Repo: Job, Probe, Alarme (Kennzahlen weg, KI-Fehler gehäuft, Live-Minuten nahe Limit, Login-Drosselung),
  Azure-Kontingent über 80 %, Dashboard „Redefluss“
- Voraussetzung vor dem ersten Deploy: DNS-A-Eintrag `redefluss` → `212.132.95.145`, kein AAAA-Eintrag

## 10. Etappen

1. **Fundament + Gespräch** – Gerüst Ktor/Svelte, Login, PWA, Docker, CI/CD, Monitoring, Gesprächsmodus,
   Fehler-Gedächtnis, Übersicht (ohne Wortschatz-Teil)
2. **Wortschatz** – Wörter des Tages, SM-2, Karten-Ablauf, Übersicht vollständig
3. **Aussprache** – Azure, WAV-Umwandlung, Laute-Anzeige, Aussprache in der Wortschatz-Wiederholung
4. **Vorstellungsgespräch live** – Realtime/WebRTC, Bericht, Kostenbremse

Jede Etappe ist einzeln lauffähig, getestet und deployt.
