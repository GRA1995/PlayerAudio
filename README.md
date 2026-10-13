# Player Audio (Android)

Player audio simplu: listă cu toate piesele de pe telefon, căutare, play/pauză,
anterior/următor, bară de derulare, amestecare și repetare. Se oprește singur
când scoți căștile și cedează sunetul la apeluri.

## Varianta 1 — APK fără să instalezi nimic (GitHub Actions)
1. Creează un depozit nou pe github.com și urcă tot conținutul acestui folder.
2. Tab-ul **Actions** → workflow-ul „Construiește APK” pornește singur (≈3–5 min).
3. Deschide rularea terminată → secțiunea **Artifacts** → descarcă `PlayerAudio-apk`.
4. Dezarhivează, copiază `app-debug.apk` pe telefon și deschide-l
   (permite „instalare din surse necunoscute” când ți se cere).

## Varianta 2 — Android Studio
File → Open → alege folderul → Build → Build APK(s).
APK-ul apare în `app/build/outputs/apk/debug/`.

Cerințe: Android 7.0 sau mai nou.
