# NAS Gallery Mount

Android App：透過 **Magisk Root** + **rclone** 唯讀掛載 SMB／NAS 媒體，並對齊 **MediaProvider** 掛載視圖，讓 **Google 相簿**能開啟 NAS 上的照片與影片。原檔留在 SMB，手機不複製媒體本體。

| 項目 | 說明 |
|---|---|
| Package | `com.nas.gallerymount` |
| 語言 | Kotlin |
| 最低條件 | 已 Root、已安裝 Magisk 的 Android 裝置 |
| 第一版驗收裝置 | Redmi 7／LineageOS 21／Magisk 31.1 |
| 規格文件 | [android-smb-google-photos-spec.md](android-smb-google-photos-spec.md) |
| 第三方工具 | [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md) |

## 為什麼需要這個 App

一般 rclone FUSE 掛載後，普通 App 看得到檔案，但 Android 14 的 **MediaProvider** 常落在另一個 Linux mount namespace，掃描同一路徑時是空目錄，導致 MediaStore 索引為 0、Google 相簿看不到。

本 App 的資料流：

1. 以 rclone SMB 唯讀掛載到共用儲存路徑（一般 App 可見）
2. 在 MediaProvider 的 namespace 建立 **bind mount**，對齊掃描器實際路徑
3. 有界批次掃描，寫入 MediaStore
4. Google 相簿從裝置媒體清單開啟；雲端備份仍由 Google 相簿自身設定決定

## 功能

- SMB 連線設定（主機、連接埠、帳密、網域、共享、子路徑、掛載點）
- 連線測試、列出共享／來源目錄
- 唯讀掛載、MediaProvider 視圖對齊、媒體掃描與進度
- 狀態查詢、中斷掃描、斷線與卸載
- 斷線後可清除本掛載點的 MediaStore 索引（不刪 SMB 原檔）
- Root／工具／裝置探測；未授權時停用 Root 操作並可重新檢查
- ADB `CommandReceiver`：無需截圖點擊即可自動化操作

**警告**：若 Google 相簿開啟裝置資料夾備份，掃描後的媒體可能上傳雲端。

## 需求

- 已 Root、已安裝 Magisk 的 Android 裝置
- 手機能路由到 SMB 主機（含 Tailscale 等 VPN）
- JDK 17+（建議 Android Studio JBR）、Android SDK
- 建置前備妥 Android ARM64 的 `rclone`、`fusermount3`、`libfuse3`（見下方建置）

手機應能獨立完成：安裝 APK → Magisk 授權 → 設定 SMB → 掛載掃描 → 用 Google 相簿開啟。無須預裝 Termux；ADB 僅供開發與診斷。

## 建置

```bash
# 1) 取得原生工具（.so 預設不進 git）
./scripts/fetch-native-tools.sh
# 並放入 libfusermount3.so（細節見 docs/THIRD_PARTY.md）

# 2) 編譯
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

建置前確認：

- `app/src/main/jniLibs/arm64-v8a/librclone.so`
- `app/src/main/jniLibs/arm64-v8a/libfusermount3.so`
- `app/src/main/jniLibs/arm64-v8a/libfuse3.so`

請使用 **Android／Termux 建置**的二進位，勿使用 glibc 的 `linux-arm64` rclone。

## 使用流程（手機獨立）

1. 安裝 APK，在 Magisk「超級使用者」授權本 App
2. 開啟 App → 確認 Root／工具／裝置探測通過
3. 填寫 SMB 設定 → 儲存 → 測試連線 → 列出來源
4. 建議先用「測試目錄」掛載小資料夾 → 連線並掃描
5. 以 Google 相簿開啟測試照片／影片
6. 斷線：預設保留 MediaStore 索引，UI 顯示「檔案不可用」；重掛後可修復
7. 清除媒體索引：手動刪除本掛載路徑的 MediaStore 列，讓 Google 相簿本地清單消失（不刪 SMB 原檔）

## ADB 指令（給 AI／自動化）

App 匯出 `CommandReceiver`，對應 UI 按鈕。建議用腳本：

```bash
chmod +x scripts/adb-cmd.sh
./scripts/adb-cmd.sh recheck
./scripts/adb-cmd.sh status
./scripts/adb-cmd.sh connect      # 連線並掃描，並輪詢 status
./scripts/adb-cmd.sh cancel      # 中斷掃描
./scripts/adb-cmd.sh disconnect
./scripts/adb-cmd.sh clear-index # 清除本掛載點 MediaStore 索引
./scripts/adb-cmd.sh logs
./scripts/adb-cmd.sh show-logs   # UI 顯示 logs
```

結果檔（可用 `run-as` 或 `su` 讀取）：

- `files/debug/last_result.txt`
- `files/debug/status.txt`
- `files/debug/app.log`

或直接：

```bash
adb shell am broadcast -a com.nas.gallerymount.cmd.CONNECT \
  -n com.nas.gallerymount/.debug.CommandReceiver
adb logcat -s NasGallery
```

## 架構摘要

| 模組 | 職責 |
|---|---|
| `root/` | `su` 白名單執行、mountinfo 解析 |
| `mount/` | rclone FUSE、fusermount3 卸載 |
| `probe/` | MediaProvider PID／路徑探測 |
| `media/` | 有界 `MediaScannerConnection` 掃描 |
| `domain/` | 連線／斷線互斥狀態機 |
| `config/` | 加密偏好 + 私有 `rclone.conf` |
| `debug/` | ADB broadcast 指令與狀態 dump |

## 驗收檢查清單

- [ ] 未 Root／未授權時停用 Root 操作並可重新檢查
- [ ] 無 Termux、無 ADB 下完成測連線→掛載→掃描→斷線
- [ ] 測試共享含 2 JPG、1 MP4、特殊檔名、`.nomedia`
- [ ] 斷線後索引仍在且標示不可用；掛載均已移除
- [ ] 重複掛載、網路中斷、目錄 busy 可重試
- [ ] Google 相簿可開原圖與播放測試影片（雲端備份另驗，需使用者同意）

## 授權與注意

- App 原始碼授權：見 repository（若尚未加入 LICENSE，請依發行需求補上）
- 隨附 rclone／libfuse 等第三方授權見 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)
- 勿將 SMB 密碼寫入日誌、命令列或共享儲存
- 僅支援已 Root 且已授權本 App 的裝置；SELinux Enforcing 下須實機驗證
