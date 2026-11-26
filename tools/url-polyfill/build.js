const esbuild = require("esbuild");
const fs = require("fs");
const path = require("path");

const banner = [
  "/*",
  " * URL and URLSearchParams for QuickJS.",
  " * whatwg-url and tr46: MIT, Copyright Sebastian Mayr.",
  " * punycode: MIT, Copyright Mathias Bynens.",
  " * webidl-conversions: BSD-2-Clause, Copyright Domenic Denicola.",
  " */",
  fs.readFileSync(path.join(__dirname, "text-encoder.js"), "utf8"),
].join("\n");

esbuild.buildSync({
  entryPoints: [path.join(__dirname, "entry.js")],
  bundle: true,
  format: "iife",
  platform: "neutral",
  mainFields: ["main"],
  target: "es2020",
  legalComments: "none",
  banner: { js: banner },
  outfile: path.join(__dirname, "../../app/src/main/resources/mygomusic/url.js"),
});
