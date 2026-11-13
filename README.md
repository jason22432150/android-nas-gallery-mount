# NAS Gallery Mount

Android App：透過 Magisk Root + rclone 唯讀掛載 SMB，並對齊 MediaProvider 掛載視圖，讓 Google 相簿能開啟 NAS 上的照片／影片。原檔留在 SMB。

規格見 [android-smb-google-photos-spec.md](android-smb-google-photos-spec.md)。

## 需求

- 已 Root、已安裝 Magisk 的 Android 裝置（第一版驗收：Redmi 7 / LineageOS 21）
- 手機能路由到 SMB 主機（含 Tailscale 等 VPN）
- JDK 17+（建議 Android Studio JBR）、Android SDK

## 建置

```bash
# 1) 取得原生工具
./scripts/fetch-native-tools.sh
# 並放入 libfusermount3.so（見 docs/THIRD_PARTY.md）

# 2) 編譯
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 使用流程（手機獨立）

1. 安裝 APK，在 Magisk「超級使用者」授權本 App  
2. 開啟 App → 確認 Root／工具／裝置探測通過  
3. 填寫 SMB 設定 → 儲存 → 測試連線 → 列出來源  
4. 建議先用「測試目錄」掛載小資料夾 → 連線並掃描  
5. 以 Google 相簿開啟測試照片／影片  
6. 斷線：預設保留 MediaStore 索引，UI 顯示「檔案不可用」；重掛後可修復  
7. 清除媒體索引：手動刪除本掛載路徑的 MediaStore 列，讓 Google 相簿本地清單消失（不刪 SMB 原檔）  

**警告**：若 Google 相簿開啟裝置資料夾備份，掃描後的媒體可能上傳雲端。

## ADB 指令操作（給 AI／自動化，無需截圖點擊）

App 匯出 `CommandReceiver`，對應 UI 按鈕。建議用腳本：

```bash
chmod +x scripts/adb-cmd.sh
./scripts/adb-cmd.sh recheck
./scripts/adb-cmd.sh status
./scripts/adb-cmd.sh connect      # 連線並掃描，並輪詢 status
./scripts/adb-cmd.sh cancel      # 中斷掃描
./scripts/adb-cmd.sh disconnect
./scripts/adb-cmd.sh clear-index # 清除本掛載點 MediaStore 索引（相簿本地清單）
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

- `root/`：`su` 白名單執行、mountinfo 解析  
- `mount/`：rclone FUSE、fusermount3 卸載  
- `probe/`：MediaProvider PID／路徑探測  
- `media/`：有界 `MediaScannerConnection` 掃描  
- `domain/`：連線／斷線互斥狀態機  
- `config/`：加密偏好 + 私有 `rclone.conf`  
- `debug/`：ADB broadcast 指令與狀態 dump  

## 驗收檢查清單

- [ ] 未 Root／未授權時停用 Root 操作並可重新檢查  
- [ ] 無 Termux、無 ADB 下完成測連線→掛載→掃描→斷線  
- [ ] 測試共享含 2 JPG、1 MP4、特殊檔名、`.nomedia`  
- [ ] 斷線後索引仍在且標示不可用；掛載均已移除  
- [ ] 重複掛載、網路中斷、目錄 busy 可重試  
