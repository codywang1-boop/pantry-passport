// Turns assets/icon.svg into the PNGs that @capacitor/assets needs (runs on Codemagic).
const sharp = require("sharp");
const fs = require("fs");
const SRC = fs.existsSync("assets/logo.png") ? "assets/logo.png" : "assets/icon.svg";
(async () => {
  await sharp(SRC).resize(1024, 1024).flatten({ background: "#5b4bff" }).png().toFile("assets/icon.png");
  const logo = await sharp(SRC).resize(900, 900).png().toBuffer();
  for (const f of ["splash.png", "splash-dark.png"]) {
    await sharp({ create: { width: 2732, height: 2732, channels: 3, background: "#5b4bff" } })
      .composite([{ input: logo, gravity: "center" }]).png().toFile("assets/" + f);
  }
  console.log("icons made");
})();
