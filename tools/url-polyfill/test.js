const fs = require("fs");
const vm = require("vm");
const path = require("path");

const code = fs.readFileSync(
  path.join(__dirname, "../../app/src/main/resources/mygomusic/url.js"),
  "utf8",
);
const sandbox = {
  console,
  Uint8Array,
  Uint16Array,
  Uint32Array,
  Int8Array,
  Int16Array,
  Int32Array,
  Float32Array,
  Float64Array,
  ArrayBuffer,
  DataView,
  Object,
  Array,
  String,
  Number,
  Boolean,
  Math,
  JSON,
  Symbol,
  Map,
  Set,
  WeakMap,
  WeakSet,
  Error,
  TypeError,
  RangeError,
  URIError,
  SyntaxError,
  Promise,
  Reflect,
  Proxy,
  Function,
  RegExp,
  Date,
  parseInt,
  parseFloat,
  isNaN,
  isFinite,
  encodeURIComponent,
  decodeURIComponent,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(code, sandbox);

function check(name, actual, expected) {
  if (actual !== expected) throw new Error(name + ": " + actual);
}

const server = new sandbox.URL("http://192.168.1.2:8096");
check(
  "emby",
  (server.origin + server.pathname).replace(/\/+$/, ""),
  "http://192.168.1.2:8096",
);
let invalid = false;
try {
  new sandbox.URL("not a url");
} catch (error) {
  invalid = error instanceof sandbox.TypeError;
}
if (!invalid) throw new Error("invalid URL did not throw TypeError");

const query = new sandbox.URLSearchParams({ op: "add", ids: "1 2" });
check("query", query.toString(), "op=add&ids=1+2");

const picture = new sandbox.URL("https://p1.music.126.net/a.jpg");
picture.searchParams.set("param", "{size}y{size}");
check("protocol", picture.protocol, "https:");
check("hostname", picture.hostname, "p1.music.126.net");
check("port", picture.port, "");
check("username", picture.username, "");
if (!picture.toString().includes("param=")) throw new Error(picture.toString());

check("relative", new sandbox.URL("c", "https://y.qq.com/a/b").href, "https://y.qq.com/a/c");
console.log("url polyfill ok");
