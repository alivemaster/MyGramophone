// Not packaged. Install this folder or a zip of it, enable plugins, tap the row.
// logcat tag Mygomusic: plugin host-probe playlists {...}
// md5 of "abc" is 900150983cd24fb0d6963f7d28e17f72
// AES-ECB PKCS7 of "hello" with key 0123456789abcdef is 674c7ef38e78cabd9cec9c125823a639
// Tap again: storage.n increases. setting stays "" until settings are saved.
// allowed needs a network path to example.com. blocked and other do not.

const MD5_ABC = "900150983cd24fb0d6963f7d28e17f72";
const AES_HELLO = "674c7ef38e78cabd9cec9c125823a639";

async function one(url, init) {
  try {
    const res = await fetch(url, init);
    const text = await res.text();
    return {
      ok: res.ok,
      status: res.status,
      example: text.includes("Example"),
      type: res.headers.get("content-type"),
      cookies: Array.isArray(res.headers.getSetCookie()),
    };
  } catch (e) {
    return { error: String(e && e.message || e) };
  }
}

export async function playlists() {
  const prev = host.storage.get("n");
  const n = (typeof prev === "number" ? prev : 0) + 1;
  host.storage.set("n", n);
  host.storage.set("box", { n, ok: true, items: [1, "x"] });
  host.storage.set("gone", 1);
  host.storage.remove("gone");
  const md5 = host.crypto.md5("abc");
  const aes = host.crypto.aesEcbEncrypt("0123456789abcdef", "hello");
  return {
    md5ok: md5 === MD5_ABC,
    aesok: aes === AES_HELLO,
    md5,
    aes,
    setting: host.setting("probe"),
    storage: host.storage.get("box"),
    gone: host.storage.get("gone") == null ? "removed" : "present",
    fetch: {
      allowed: await one("https://example.com/"),
      noRedirect: await one("https://example.com/", { redirect: "error" }),
      blocked: await one("https://example.net/"),
      other: await one("https://www.example.com/"),
    },
  };
}
