// Turns assets/icon.svg into the PNGs that @capacitor/assets needs (runs on Codemagic).
const sharp = require("sharp");
(async () => {
  await sharp("assets/icon.svg").resize(1024, 1024).flatten({ background: "#123f3a" }).png().toFile("assets/icon.png");
  const logo = await sharp("assets/icon.svg").resize(900, 900).png().toBuffer();
  for (const f of ["splash.png", "splash-dark.png"]) {
    await sharp({ create: { width: 2732, height: 2732, channels: 3, background: "#123f3a" } })
      .composite([{ input: logo, gravity: "center" }]).png().toFile("assets/" + f);
  }
  console.log("icons made");
})();
