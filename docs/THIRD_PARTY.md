# 第三方工具與授權

本 App 執行期透過 Root 呼叫隨附的原生工具（以 `lib*.so` 名稱打進 APK `jniLibs`，從 `nativeLibraryDir` 執行）。

| 元件 | 建議版本 | 來源 | 授權 |
|---|---|---|---|
| rclone | v1.74.3-termux（與規格實測一致） | Termux 套件／https://rclone.org | MIT |
| fusermount3 | libfuse3 3.x（Termux root-repo） | https://github.com/termux/termux-packages/tree/master/root-packages/libfuse3 | GPL-2.0（libfuse） |
| libfuse3.so | 同上 | 同上 | GPL-2.0 |

## 取得方式

優先從已 Root、已安裝 Termux 工具的驗證機拉取：

```bash
./scripts/fetch-native-tools.sh
```

或手動指定本機檔案：

```bash
RCLONE_SRC=... FUSEMOUNT3_SRC=... LIBFUSE3_SRC=... ./scripts/fetch-native-tools.sh
```

建置前確認：

- `app/src/main/jniLibs/arm64-v8a/librclone.so`
- `app/src/main/jniLibs/arm64-v8a/libfusermount3.so`
- `app/src/main/jniLibs/arm64-v8a/libfuse3.so`

`.so` 二進位預設不進 git（見 `.gitignore`），發行 APK 前必須在建置機備妥。對應 `.sha256` 可一併保存於本機。

## 注意

- 請使用 **Android／Termux 建置**，勿使用 glibc 的 `linux-arm64` rclone。
- 勿將 SMB 密碼寫入日誌或命令列。
- SELinux Enforcing 下須在目標機驗證可執行。
