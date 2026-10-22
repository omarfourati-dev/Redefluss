# Gäste per Einladung – Entwurf

Stand 22.10.2026, von Omar freigegeben („ja mach das mit deinen Limits“).

## Ziel
Redefluss bleibt privat, aber der Besitzer kann einzelne Personen einladen. Jede Person hat ihr eigenes Konto mit
eigenem Fehler-Gedächtnis, Wortschatz, Gesprächsverlauf und eigenen Tageslimits. Niemand sieht die Daten eines anderen.

## Rollen
- `owner`: das bisherige Konto aus `OWNER_EMAIL`. Darf Einladungen erstellen, zurückziehen und Gäste entfernen.
- `guest`: entsteht nur über eine Einladung. Darf alles üben, aber nichts verwalten.

## Daten
Migration `V3__guests.sql`:
- `app_user.role` (owner/guest); das vorhandene Konto wird `owner`.
- `user_id` (FK auf `app_user`, `ON DELETE CASCADE`) in `mistake`, `practice_session`, `vocab_card`, `usage_day`;
  vorhandene Zeilen gehören dem Besitzer.
- Eindeutigkeit pro Konto: `(user_id, normalized)`, `(user_id, word_key)`, Primärschlüssel `usage_day (user_id, day)`.
- `invitation`: E-Mail, SHA-256 des Tokens (nie das Token selbst), Ablauf (7 Tage), `used_at`.

Alle Repository-Methoden bekommen die `userId` als ersten Parameter; jede Abfrage filtert danach.

## Limits
| | Besitzer | Gast | alle zusammen |
|---|---|---|---|
| Gesprächsrunden/Tag | `TURNS_PER_DAY` (300) | `GUEST_TURNS_PER_DAY` (100) | `TOTAL_TURNS_PER_DAY` (500) |
| Live-Interview Minuten/Tag | `LIVE_MINUTES_PER_DAY` (30) | `GUEST_LIVE_MINUTES_PER_DAY` (15) | `TOTAL_LIVE_MINUTES_PER_DAY` (60) |
| Aussprache-Versuche/Tag | `PRONUNCIATIONS_PER_DAY` (100) | `GUEST_PRONUNCIATIONS_PER_DAY` (30) | – |
| Wortschatz-Wiederholungen/Tag | `VOCAB_REVIEWS_PER_DAY` (60) | `GUEST_VOCAB_REVIEWS_PER_DAY` (30) | – |
| Azure-Sekunden/Monat | – | – | `AZURE_SECONDS_PER_MONTH` (16 200, wie bisher gemeinsam) |

Runden und Live-Minuten prüfen Konto- und Gesamtgrenze in einer Transaktion unter einem Advisory-Lock.

## Einladung
1. Besitzer gibt unter „Konto“ eine E-Mail ein → `POST /api/invitations` → einmalig das Token.
2. Die Oberfläche zeigt den Link `https://…/einladung#<token>` zum Kopieren (das Token steht im Fragment und landet
   so nie in Server- oder Proxy-Logs). Redefluss verschickt selbst keine E-Mails.
3. Die eingeladene Person öffnet den Link, sieht ihre E-Mail (`POST /api/invitations/preview`), setzt ihr Passwort
   (`POST /api/invitations/accept`) und ist angemeldet. Die Einladung ist danach verbraucht.
4. Fehlversuche mit Tokens sind pro IP gedrosselt (5 in 15 Minuten).

Verwaltung (nur Besitzer): `GET /api/invitations`, `DELETE /api/invitations/{id}` (nur offene),
`GET /api/users`, `DELETE /api/users/{id}` (nur Gäste; löscht alle ihre Daten).
