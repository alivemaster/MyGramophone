if (typeof TextEncoder === "undefined") {
  function utf8Bytes(text) {
    const bytes = [];
    const input = String(text);
    for (let i = 0; i < input.length; i++) {
      let code = input.charCodeAt(i);
      if (code >= 0xd800 && code <= 0xdbff && i + 1 < input.length) {
        const next = input.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          code = ((code - 0xd800) << 10) + (next - 0xdc00) + 0x10000;
          i++;
        }
      }
      if (code < 0x80) bytes.push(code);
      else if (code < 0x800) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      else if (code < 0x10000) {
        bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      } else {
        bytes.push(
          0xf0 | (code >> 18),
          0x80 | ((code >> 12) & 0x3f),
          0x80 | ((code >> 6) & 0x3f),
          0x80 | (code & 0x3f),
        );
      }
    }
    return new Uint8Array(bytes);
  }

  function utf8String(bytes) {
    let out = "";
    for (let i = 0; i < bytes.length;) {
      const b0 = bytes[i++];
      let code;
      if (b0 < 0x80) code = b0;
      else if (b0 < 0xe0) code = ((b0 & 0x1f) << 6) | (bytes[i++] & 0x3f);
      else if (b0 < 0xf0) {
        code = ((b0 & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
      } else {
        code = ((b0 & 0x07) << 18)
          | ((bytes[i++] & 0x3f) << 12)
          | ((bytes[i++] & 0x3f) << 6)
          | (bytes[i++] & 0x3f);
      }
      if (code > 0xffff) {
        code -= 0x10000;
        out += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff));
      } else {
        out += String.fromCharCode(code);
      }
    }
    return out;
  }

  function Encoder() {}
  Encoder.prototype.encode = function (text) { return utf8Bytes(text); };
  function Decoder() {}
  Decoder.prototype.decode = function (input) {
    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input || []);
    return utf8String(bytes);
  };
  globalThis.TextEncoder = Encoder;
  globalThis.TextDecoder = Decoder;
}
