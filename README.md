# Track.it Android

Android-Hülle für https://treckit-simon-app.netlify.app mit einem Modul, das die Bildschirmzeit
(Nutzungsdaten) und Entsperrungen des Geräts liest. Die Web-App selbst wird weiter über Netlify aktualisiert.

Der Build läuft automatisch bei GitHub (Reiter **Actions**). Die fertige APK-Datei steht danach im Ablauf unter **Artifacts** (`Treckit-App`).

Hinweis: `treckit.keystore` ist nur ein einfacher Signaturschlüssel für private Test-Installationen, kein Geheimnis. Für den Play Store wird später ein eigener Schlüssel nötig.
