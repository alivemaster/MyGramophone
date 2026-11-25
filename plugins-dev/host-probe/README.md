# host-probe

不打进 APK。把这个目录打成 zip（根上要有 `plugin.json`），或在手机上选这个文件夹安装。

打开「启用 mygo-music 插件支持」，点这一行。logcat 过滤 `Mygomusic`，看 `plugin host-probe playlists`。

- `md5ok` / `aesok` 为 true
- `setting` 为 `""`（设置页还没做，没有写入）
- `gone` 为 `removed`，`storage.n` 第一次是 1，再点一次加 1
- `fetch.blocked` 和 `fetch.other` 带 `error`，且含 `not allowed`
- `fetch.allowed.ok` 为 true 需要手机能打开 `https://example.com/`
