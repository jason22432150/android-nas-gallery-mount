# Android SMB 媒體掛載與 Google 相簿整合實作規格

## 1. 目標與交付

開發一款 Android App，讓使用者設定 SMB 來源，將照片與影片以唯讀方式呈現在 Android 共用儲存空間，並讓 Android 的 MediaProvider 掃描、寫入 MediaStore，使 Google 相簿能從裝置媒體清單開啟檔案。原檔留在 SMB。App 應提供連線測試、掛載、索引、狀態查詢及卸載功能。

第一版以已 Root 的 Redmi 7、Android 14／LineageOS 21、Magisk 31.1 為驗證環境。ADB 是開發和故障診斷工具。正式 App 應直接透過 Magisk 的 su 執行固定的 Root 操作。以 Kotlin 實作，畫面至少包含連線設定、來源選擇及掛載狀態。預設提供一個測試資料夾流程，正式路徑由使用者指定。

### 手機獨立操作是交付條件

從已 Root 且裝有 Magisk 的 Redmi 7 開始，使用者應能在手機上安裝 APK、授權 Root、設定及測試 SMB、連線掛載、掃描媒體、查詢狀態與斷線。手機須能直接存取 SMB 主機，若來源經 Tailscale 或其他 VPN 提供，連線由手機建立。整個流程應在 USB 拔除、USB 偵錯及無線偵錯關閉時運作。

APK 須隨附 Android ARM64 的 `rclone`、`fusermount3` 和所需相依元件。驗收裝置無須預裝 Termux。App 在手機上透過 Magisk 啟動工具、管理掛載及執行媒體掃描，透過 GUI 回報結果。安裝及管理 Magisk 由使用者在既有 Root 環境中完成。

### Root 是必要條件

App 僅支援已 Root 且已授權本 App 使用 Root 的 Android 裝置。首次啟動及每次連線或斷線前，App 應執行固定的 `su -c id` 檢查，確認回傳 `uid=0(root)`，並在設定頁顯示授權狀態。找不到 `su`、授權遭拒或逾時時，停用需要 Root 的操作，顯示在 Magisk 的「超級使用者」頁面授權本 App 的步驟，並提供「重新檢查」按鈕。設定檔仍可在 GUI 編輯及儲存。

### rclone 功能須可由 GUI 操作

使用者應能在 App 內完成以下操作。ADB 與 Termux 指令保留作為開發診斷及重現既有實測的參考。

| GUI 操作 | App 行為 |
|---|---|
| 新增或編輯 SMB 連線 | 輸入主機、連接埠、帳密、網域、共享名稱、子資料夾及手機掛載位置。可儲存設定並遮蔽密碼。 |
| 測試連線與選擇來源 | 用 rclone 唯讀驗證 SMB 認證及來源可讀性，可列出共享及子資料夾。伺服器禁止列舉共享時，允許手動輸入共享名稱並直接測試。 |
| 連線 | 啟動 rclone 唯讀掛載並驗證掛載點可讀。若此設定啟用 Google 相簿整合，接續驗證 MediaProvider 的掛載視圖，並按使用者選擇的範圍掃描。 |
| 斷線 | 停止提交新的媒體掃描，卸載本 App 建立的 MediaProvider bind mount，再卸載 rclone 掛載，最後重新查驗兩個掛載視圖。若 Android 已接手掃描，畫面應說明該批可能繼續執行。斷線保留 SMB 原檔及已完成的雲端備份。 |
| 查看狀態與重試 | 顯示連線中、已掛載、掃描中、斷線中、已斷線或失敗，並標明失敗階段。卸載遇到目錄使用中時，顯示原因並提供重試。只在驗證完成後顯示「已斷線」，並防止連線和斷線同時執行。 |

## 2. 已有證據與限制

| 項目 | Redmi 7 的實測結果 |
|---|---|
| USB 與 Root | ADB USB 正常。Magisk 授權 Shell 後，su -c id 回傳 uid=0(root)，SELinux 為 Enforcing。正式 App 的套件需在 Magisk 另外授權。 |
| 核心 | 4.9.337-perf-g8aab452e4acd。核心設定有 `# CONFIG_NETWORK_FILESYSTEMS is not set`，檔案系統清單無 CIFS。 |
| FUSE | Magisk Root 可開啟 /dev/fuse。Termux 的 rclone v1.74.3-termux 搭配 fusermount3 3.16.2 通過本機唯讀掛載及讀取。 |
| SMB | SMB 來源為 nas:raid/data/immich/UPLOAD/upload。主機為 100.69.221.57。正式掛載點為 /storage/emulated/0/DCIM/Camera/smb。範例設定檔不含帳密。 |
| 一般 App 視圖 | 一般 ADB Shell 可以列出正式掛載，Google 相簿程序的掛載表也看得到。 |
| MediaProvider 視圖 | 原始掛載時，掃描器的 /storage/emulated/0/DCIM/Camera/smb 是空目錄。同一程序可從 /mnt/user/0/emulated/0/DCIM/Camera/smb 看到 rclone 檔案。 |
| 單張測試 | 以檔案 bind mount 對齊兩個視圖，掃描後取得 MediaStore 圖片索引。媒體 URI 前 4 KB 與 SMB 原檔雜湊一致，使用者確認 Google 相簿可開啟。 |
| 小資料夾測試 | 唯讀 bind mount 整個 Immich 子資料夾到 Pictures/SMBFolderTest.RBaUpJ。內有 1 張 JPG，成功索引並核對 URI 資料，使用者確認可開啟。 |
| 正式目錄 | 對 MediaProvider 加入整目錄 bind mount 後發起掃描。中途查到 32 張照片、3 段影片。最終數量、Google 相簿的正式目錄顯示、影片播放與雲端備份均未確認。 |

使用者後來要求停止 ADB 操作。停止了後續查詢，先前已提交給 MediaProvider 的掃描是否完成或取消未知。接手開發時，等使用者另行授權才重新連線。以上掛載及索引可能因程序重啟或手機重開機而改變。

## 3. 為什麼需要兩個掛載視圖

Android 14 的 MediaProvider 使用自己的 Linux 掛載命名空間。這台手機上，掃描器的 /storage 路徑走底層 sdcardfs，而一般 App 的同名路徑能看到 rclone FUSE。把 SMB 移到 DCIM 或 Camera 底下也會遇到同一個問題。MediaStore 在原始掛載方式下查到 0 張照片和 0 段影片。

實作資料流如下。

1. rclone 使用 SMB 後端透過手機的網路讀取共享，並把檔案呈現在一般 App 看得到的路徑。
2. Root 程序確認 MediaProvider PID 及掛載視圖，從該程序已看得到的同一份 rclone 掛載建立 bind mount，覆蓋掃描器實際使用的同名空目錄。
3. MediaProvider 以本機媒體路徑掃描檔案並建立 MediaStore 索引。
4. Google 相簿透過裝置媒體索引取得檔案。Google 相簿內的雲端備份由 App 自身設定控制。

bind mount 只建立另一個檔案系統入口。測試時沒把照片原檔複製到手機，Android 或 Google 相簿仍可能建立縮圖和快取。

## 4. 參考操作與指令

以下是開發診斷用的參考流程。正式版的使用者驗收依第 6 節在手機 GUI 完成。先使用小型測試子資料夾，正式目錄可含大量內容。Google 相簿若已開啟相關備份，掃描後媒體可能上傳。畫面應顯示此次會掃描的路徑和範圍。

### 4.1 準備工具和 SMB 設定

在手機的 Termux App 逐行執行。

```sh
pkg install rclone
pkg install root-repo
pkg update
pkg install libfuse3
rclone version
fusermount3 --version
rclone mount --help
rclone config
```

- 第一行安裝支援 SMB 的 rclone。
- 第二、三行啟用 Root 工具套件庫並更新索引。
- 第四行安裝內含 fusermount3 的 libfuse3。
- 接下來三行驗證二進位可執行及掛載指令存在。
- 最後一行開啟互動式設定。連線名稱填 nas，後端選 smb，host 填 100.69.221.57，port 預設 445，依伺服器填帳號、密碼及網域。如果該位址要透過 Tailscale 存取，手機也要連到能路由該位址的網路。

```sh
rclone lsd nas:
rclone lsf 'nas:raid/data/immich/UPLOAD/upload' --max-depth 1
rclone config file
```

- 第一行列出共享名稱。如果伺服器禁止列舉共享，可直接指定已知的共享進行下一步。
- 第二行唯讀列出指定來源的第一層內容，用於驗證連線、認證和來源路徑。
- 第三行顯示設定檔位置，讓 Root 程序能顯式指定設定檔。請勿把設定內容寫入記錄。

### 4.2 建立一般 App 可見的唯讀掛載

在 Mac 執行 `adb -d shell`，以 USB 開啟手機 Shell。接著在手機 Shell 執行 `su -M`，進入 Magisk 全域掛載命名空間。執行 `id` 應看到 uid=0(root)，表示授權成功。

以下路徑以實測 Termux 配置為例。先確認目標是專用空資料夾。如果已有掛載，查詢 mountinfo 並核對來源再決定重用或卸載。背景模式請用 rclone 的絕對路徑，先前用短指令名曾在 daemon 啟動時遇到 `open /rclone: no such file or directory`。

```sh
B=/data/data/com.termux/files/usr/bin
export PATH="/system/bin:$B:$PATH"
D=/storage/emulated/0/DCIM/Camera/smb
S=nas:raid/data/immich/UPLOAD/upload
C=/data/data/com.termux/files/home/.config/rclone/rclone.conf
/system/bin/mkdir -p "$D"
"$B/rclone" mount "$S" "$D" --config "$C" --read-only --allow-other \
  --daemon --daemon-wait 20s
grep -F " $D " /proc/self/mountinfo
/system/bin/ls -A "$D"
```

- PATH 讓 rclone 找到 fusermount3，並優先使用 Android 的系統工具。
- mkdir 建立目標資料夾。rclone 以唯讀方式掛載，allow-other 讓其他程序有機會讀取，仍受 Android 權限及 SELinux 管理。
- grep 檢查精確掛載點、fuse.rclone、來源及 ro 選項。正式程式應解析 mountinfo 欄位並解碼跳脫路徑。
- ls 驗證掛載內容可列出。開發時另開一般 ADB Shell 重驗，確認掛載對一般程序可見。

### 4.3 讓 MediaProvider 看得到掛載

以下在同一個 Root Shell 執行。只對專用目錄操作。先確認程序 PID、掛載來源和傳播狀態。這段適用於本機驗證環境，正式 App 必須探測 OEM 差異並在條件不符時停止。

```sh
P=$(pidof com.android.providers.media.module)
A=/mnt/user/0/emulated/0/DCIM/Camera/smb
grep -F " $A " "/proc/$P/mountinfo"
nsenter -t "$P" -m -- /system/bin/ls -A "$A"
grep -F " $D " "/proc/$P/mountinfo"
```

- pidof 找出掃描器程序。部分 ROM 使用 com.google.android.providers.media.module 或其他程序配置，請以實際媒體 Provider 和程序對應確認。若回傳多個 PID，不能把整串交給 nsenter。
- 第一個 grep 和 ls 檢查掃描器確實看得到同一份唯讀 rclone 來源。
- 第二個 grep 查看目標是否已經掛載。若已掛載正確來源就重用，遇到其他來源就回報衝突。

確認目標為掃描器視圖內的預期空目錄後，執行以下三行。

```sh
nsenter -t "$P" -m -- /system/bin/mount --bind "$A" "$D"
grep -F " $D " "/proc/$P/mountinfo"
/system/bin/ls -A "/proc/$P/root$D"
```

- 第一行在 MediaProvider 的掛載命名空間建立目錄 bind mount。
- 第二行核對此層是正確的 rclone 掛載且維持 ro。
- 第三行從 Root 觀察掃描器的路徑，確認目錄有內容。這只證明掛載視圖和 Root 可讀，掃描器以自身權限成功讀取仍須由下一步驗證。

### 4.4 掃描與查詢結果

單檔測試可使用下列廣播。FILE 應是可讀的完整媒體路徑，正式程式應正確建構及編碼 file URI。

```sh
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$FILE"
```

這個指令只發出掃描請求，完成條件是查得到該筆索引。App 第一版可評估使用公開的 MediaScannerConnection.scanFile，逐檔提交並等待回呼，再以 MediaStore 驗證。本機正式目錄曾使用以下 Root 呼叫，實測在背景持續增加索引，完整結束狀態未知。應視為平台相依的診斷手段。

```sh
content call --uri content://media --method scan_file --arg "$D"
```

這會請目前 ROM 的媒體 Provider 掃描指定路徑。呼叫可能長時間執行。停止等待或關閉 ADB 不代表已提交的掃描立即取消。

```sh
content query --uri content://media/external/images/media \
  --projection _id --where "relative_path LIKE 'DCIM/Camera/smb/%'"
content query --uri content://media/external/video/media \
  --projection _id --where "relative_path LIKE 'DCIM/Camera/smb/%'"
```

兩行分別查詢照片與影片索引。正式 App 透過 ContentResolver 使用 selectionArgs 或對應的查詢參數，避免拼接使用者輸入。Android 14 的媒體權限與部分相片存取會影響 App 可查到的資料，應在測試中區分索引不存在和查詢權限不足。若要驗證某筆索引，從對應的媒體 URI 讀取測試資料並與 SMB 原檔比對，最後在 Google 相簿確認原圖及影片播放。

## 5. App 實作要求

| 元件 | 行為 |
|---|---|
| 設定與憑證 | 支援主機、連接埠、共享、子路徑、帳號與可選網域。憑證在裝置端儲存並限制檔案權限。若需產生 rclone.conf，放在私有路徑，避免將密碼放在命令列、日誌或共享儲存。 |
| Root 執行器 | 用 su 取得最小範圍的必要權限。固定允許的命令、驗證路徑，可靠引用每個參數。來源或掛載路徑不得跳脫允許的目錄。處理含空白、引號及非 ASCII 檔名。 |
| 工具來源 | 正式 APK 隨附來源可追溯的 Android ARM64 工具與相依元件，記錄版本、雜湊及授權。開發時可用 Termux 重現。驗證 APK 安裝後的工具執行位置、動態連結與 Root 環境的 PATH。Android 10 起，一般 App 直接執行可寫私有目錄內的二進位檔受到限制。工具打包與執行方式須在目標裝置以 SELinux Enforcing 實測。 |
| 掛載管理 | 將設定的來源、目標、實際 mountinfo、程序身分和 MediaProvider 命名空間視圖做核對。重複啟動時重用正確掛載。避免重疊目標及並行啟停。 |
| 媒體索引 | 對支援的格式做有界批次掃描，記錄已發現、已提交、已索引、跳過與失敗數。尊重 .nomedia。先小資料夾試跑，再由使用者啟動正式掃描。 |
| 狀態與進度 | 區分 SMB 已連線、FUSE 已掛載、掃描器可見、索引進行中與完成。Google 相簿可讀和雲端備份各自顯示驗證狀態，無 App 證據時標示待驗證。 |
| 恢復與清理 | 持久化設定，並在重開機、網路恢復或 MediaProvider 換 PID 後重新探測。部分失敗時只回收本次建立的掛載。卸載時先停止提交新掃描，再移除自有的掃描器 bind mount，最後卸載自有的 rclone 掛載。 |

卸載前核對精確目標、來源與掛載識別，再對掃描器命名空間執行 `nsenter -t "$P" -m -- /system/bin/umount "$D"`。此指令移除掃描器的掛載入口。接著以 `"$B/fusermount3" -u "$D"` 卸載 rclone 掛載。最後查詢兩個視圖的 mountinfo；若仍有索引，針對本 App 記錄的路徑做重新掃描或經驗證的清理。遠端檔案及其他 App 的索引不得刪除。程序換 PID 時先重新確認命名空間和掛載歸屬。

## 6. 故障定位與驗收

| 現象 | 檢查 |
|---|---|
| pkg 找不到 | 確認指令在 Termux App 執行，ADB 的 Android Shell 預設沒有 pkg。 |
| rclone 或 fusermount3 找不到 | 正式版檢查 APK 隨附工具的安裝位置、ABI、動態相依性和 Root PATH。使用 Termux 重現時，libfuse3 來自 root-repo。 |
| 掛載點不存在或被占用 | 驗證目標存在且是專用空資料夾。若已掛載，先查精確來源與擁有者。 |
| Shell 看得到、Google 相簿沒有照片 | 先比對 MediaProvider 的兩個視圖及 MediaStore 索引。掃描器看到空目錄時，先處理 bind mount。 |
| 掃描器可見、索引仍為零 | 檢查實際掃描結果、讀取權限、.nomedia、媒體格式及 URI 編碼。 |
| 索引有資料、相簿打不開 | 驗證 MediaStore URI 可讀、App 權限、照片載入與影片播放；Root 讀取成功不足以證明一般 App 可讀。 |
| 大量掃描很慢 | 觀察索引數是否持續增加。UI 不阻塞，避免重送同一批；取消時區分尚未提交的項目與已交給 Android 的掃描。 |

驗收使用專用測試共享，至少有兩張可辨識 JPG、一段可播放的 MP4、一個含空白或非 ASCII 字元的檔名，以及一個用 .nomedia 排除的資料夾。驗證正常檔案都有索引、URI 可讀、Google 相簿能開原圖及播放影片。再測重複掛載、網路中斷、Provider 重啟及卸載，確認索引狀態和實際掛載一致。雲端備份是獨立驗收項目，只有在使用者同意後測試，並從 Google 相簿查看備份狀態。

### Root 與手機獨立操作驗收

1. 使用未 Root、Root 未授權及 Root 已授權的裝置或測試環境，確認 GUI 顯示正確狀態、限制 Root 操作，並能在完成授權後重新檢查。
2. 在未安裝 Termux 的已 Root Redmi 7 上，拔除 USB 並關閉 USB 偵錯及無線偵錯。直接在手機安裝 APK、授權 App 使用 Magisk，從 GUI 新增 SMB 設定、測試連線、選擇來源、唯讀掛載、掃描、查詢索引並斷線。以 Google 相簿開啟一張測試照及播放一段測試影片。
3. 驗證連線後掛載為唯讀，斷線後 rclone 掛載及 App 自建的 MediaProvider bind mount 均已移除。模擬帳密錯誤、網路中斷及目錄使用中，確認 GUI 能顯示實際狀態及提供重試。

## 7. 來源

- [Termux libfuse3 套件](https://github.com/termux/termux-packages/tree/master/root-packages/libfuse3)
- [rclone SMB](https://rclone.org/smb/) · [rclone mount](https://rclone.org/commands/rclone_mount/)
- [Magisk 工具](https://topjohnwu.github.io/Magisk/tools.html) · [Magisk 31.1 發行說明](https://github.com/topjohnwu/Magisk/releases/tag/v31.1)
- [Android 14 MediaProvider 原始碼](https://android.googlesource.com/platform/packages/providers/MediaProvider/%2B/refs/tags/android-14.0.0_r30/src/com/android/providers/media/fuse/ExternalStorageServiceImpl.java#89) · [共用媒體儲存](https://developer.android.com/training/data-storage/shared/media) · [MediaScannerConnection](https://developer.android.com/reference/android/media/MediaScannerConnection)
- [Google 相簿裝置資料夾與備份](https://support.google.com/photos/answer/9343965?hl=zh-Hant)

- [Android 10 對可寫目錄中執行檔的限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
