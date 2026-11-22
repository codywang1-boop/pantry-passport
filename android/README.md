# Pantry Passport (Android)

Package: com.pantrypassport.app. The app is the web page in app/src/main/assets/www,
shown in a WebView. AI features call the Cloudflare server pantry-passport-ai.

Premium = Google Play subscription `pantry_premium`, base plan `monthly` ($4.99),
offer with a 14-day free trial. Premium unlocks AI dish ideas, More dishes and photo
scanning; the recipe book, Show all dishes and typed recipes stay free.

- Debug app: gradlew.bat assembleDebug
- Store bundle: run make-signed-bundle.ps1 (creates Documents\Keys\pantrypassport-upload.jks
  the first time; keep that file and its password safe).
- To update: copy the new www files into app/src/main/assets/www, raise versionCode and
  versionName in app/build.gradle.kts, run make-signed-bundle.ps1 again.
