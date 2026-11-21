# Pantry Passport — iPhone app

**Web app link (any iPhone):** https://codywang1-boop.github.io/pantry-passport/ — open in Safari, tap Share → Add to Home Screen. Every push to `main` updates it automatically.

Same app as the web version, wrapped for iPhone with Capacitor. It's built the same way as StrideBeat: Codemagic builds it on a cloud Mac, and Sideloadly installs it.

## What works on the iPhone right away
- Type ingredients, pick a food culture and get dishes from the built-in recipe book (64 dishes, 14 food cultures)
- My recipes: type your own recipes, edit or delete them, and add a photo of the dish (saved on the phone)
- Saved recipes, shopping list

## What needs the AI server (optional, see the last section)
- AI dish search with unlimited "More dishes"
- Reading ingredients from a photo
- Scanning a recipe from a photo

## Build the .ipa (no Mac needed)
1. On github.com (account codywang1-boop), make a new **private** repo called `pantry-passport`.
2. Upload everything in this folder **except** `node_modules`, `ios` and `build` (drag the files onto the repo page, then Commit).
3. In Codemagic: Add application → GitHub → `pantry-passport` → it finds `codemagic.yaml`.
4. Start the workflow **"Pantry Passport iOS (unsigned .ipa for Sideloadly)"**.
5. When it's done (about 10–15 minutes), download `PantryPassport-unsigned.ipa` from Artifacts.

## Install on your iPhone
1. Plug the iPhone into the PC and open **Sideloadly**.
2. Drag in `PantryPassport-unsigned.ipa` and sign in with your Apple ID. Then tap Start.
3. On the iPhone, open Settings → General → VPN & Device Management → trust your Apple ID.
4. With a free Apple ID the app works for 7 days, then reinstall it with Sideloadly. A paid Apple Developer account ($99 a year) is needed for TestFlight and the App Store.

## Turn on AI (optional)
You pay Anthropic per use, usually a few cents per search or scan.
1. Make an API key at console.anthropic.com (Settings → API keys) and add some credit.
2. Make a free Cloudflare account. Then, in this folder's `ai-server`, run:
   ```
   npx wrangler login
   npx wrangler secret put ANTHROPIC_API_KEY    (paste the key)
   npx wrangler secret put APP_KEY              (make up a long password)
   npx wrangler deploy
   ```
   It prints an address like `https://pantry-passport-ai.<you>.workers.dev`.
3. Open `www/ai-bridge.js` and fill in `AI_URL` (that address) and `APP_KEY` (the same password).
4. Upload the changed file to GitHub and run the Codemagic build again.

Never put the Anthropic key itself in the app. It stays on the server.
