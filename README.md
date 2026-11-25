# AURION

Privater, lokaler Sprachassistent für Android – Deutsch & Englisch.

- **Lokales LLM** (MediaPipe / LiteRT, z. B. Qwen 2.5) – läuft komplett offline auf dem Gerät
- **Als digitaler Assistent registriert** – Start über Seitentaste, Assistent-Geste oder Home-Taste lang drücken
- **Erinnerungen** mit dauerhafter Benachrichtigung, bis sie per Button **„Quittieren“** bestätigt werden
  (Wegwischen bringt sie still zurück, Neustart-fest, „10 Min später“-Button)
- **Bring!-Einkaufsliste**: Artikel hinzufügen, entfernen, vorlesen
- **Wetter** kostenlos über Open-Meteo (Standort oder fester Ort, auch „Wetter in Wien“)
- **Stimmenauswahl** für Deutsch und Englisch, Sprechtempo einstellbar
- **Gedächtnis**: „Merk dir …“ / „Remember that …“ – fließt in alle Antworten ein
- Siri-artiges Overlay mit animiertem Orb im Aurion-Look

## Installation

APK aus den [Releases](https://github.com/b05at/AURION/releases) installieren. Updates gibt's danach direkt in der App
(Einstellungen › App › Nach Updates suchen).

## Einrichtung

1. App öffnen, Mikrofon & Benachrichtigungen erlauben
2. Einstellungen › Assistent › **Standard-Assistent** → „Digitaler Assistent“ = Aurion
   - Samsung: Einstellungen › Erweiterte Funktionen › Seitentaste › Gedrückt halten › Digitaler Assistent
3. Einstellungen › Lokales Sprachmodell › **Herunterladen** (Qwen 2.5 1.5B, ≈ 1,6 GB, WLAN empfohlen)
4. Einstellungen › Bring! › mit Bring!-Konto anmelden und Liste wählen

## Beispiele

| Deutsch | English |
|---|---|
| Erinnere mich in 20 Minuten an die Wäsche | Remind me in 20 minutes to check the oven |
| Erinnere mich morgen um halb 8 an den Müll | Remind me tomorrow at 7:30 am to call Anna |
| Setz Milch, 2 Packungen Butter und Eier auf die Einkaufsliste | Add milk and eggs to my shopping list |
| Was steht auf der Einkaufsliste? | What's on my shopping list? |
| Merk dir, dass mein Auto in der Tiefgarage steht | Remember that my car is parked in the garage |
| Welche Erinnerungen habe ich? | What reminders do I have? |
| Wie wird das Wetter morgen? | Will it rain tomorrow in London? |

## Technik

Kotlin · Jetpack Compose · MediaPipe LLM Inference · VoiceInteractionService · AlarmManager · Bring! REST-API.
Spracherkennung nutzt den On-Device-Erkenner des Systems; Aurion leitet als Assistent registrierte Spracherkennung
an den System-Erkenner weiter, damit andere Apps weiter funktionieren.

Builds laufen über GitHub Actions; jeder Push auf `main` erzeugt ein signiertes Release.
