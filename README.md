# DeepSeekMaterialTheme

把 DeepSeek 安卓客户端（`com.deepseek.chat`）的**自研设计系统**替换成 Material 3 / Material You 配色的 LSPosed 模块。

已在此版本下测试通过：

- 目标 App：DeepSeek **2.5.3**（versionCode 275）
- 模块框架：LSPosed 2.1.1（提供 Xposed API **102**）

若激活模块后界面没有变化，请进入 DeepSeek 应用内部的“设置”界面，然后点按“Material 设计”按钮，即可切换原版 DeepSeek UI 与 Material UI。

## 构建

需要 JDK 21 与 Android SDK。先复制 `local.properties.example` 为 `local.properties` 并填入本机 SDK 路径：

```properties
sdk.dir=/path/to/android-sdk
```

然后执行：

```bash
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。

## 许可证

本项目以 **GNU 通用公共许可证第 3 版或更高版本**（GPL-3.0-or-later）授权发布。

```
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

完整许可证文本见 [LICENSE](LICENSE)。
