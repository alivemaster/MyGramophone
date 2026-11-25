# demo-extended

不打进 APK。结构和原来的 demo 插件一样：首页、发现、歌单「Favourites」、问候语设置、喜欢、专辑、播客和电台都还在。多出来的只有第四首 `The Photo`（中文名「这张照片」）。

这首用工作区里的 `cover.jpg` 当封面。歌词是五行 LRC，带对应的英文翻译。音频仍是 demo 插件的 `tone.mp3`，大约五秒。前三首和 demo 一样，没有封面。

把这个目录打成 zip（根上要有 `plugin.json`），或在手机上选这个文件夹安装。可以和原来的 demo 一起装，id 是 `demo-extended`。

`index.js` 和 `tone.mp3` 来自 mygo-music 的 `examples/demo-plugin`，许可证是 GNU AGPL-3.0，许可文本在 `LICENSE-AGPL`。`cover.jpg` 是自己拍的照片。
