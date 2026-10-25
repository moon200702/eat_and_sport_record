# 日常食動 · Eat & Sport Record

以 Kotlin 撰寫的原生 Android App，協助記錄每日飲食與運動。介面使用繁體中文。

[下載 Android 測試版 APK（1.1.0）](releases/eat-and-sport-record-1.1.0-debug.apk?raw=true) · [APK 與檔案驗證說明](releases/README.md)

## 功能

- 首頁提供每日統計，以及飲食、運動紀錄入口。
- 紀錄頁提供一週日期列、類型切換與依時間排列的筆記。
- 每筆可填名稱、日期、時間與多行內容，熱量及運動分鐘數為選填。
- 可編輯或刪除筆記；圖片及影片使用 Android 系統檔案選取器加入，支援多選。
- 圖片可預覽、點開放大；影片可在 App 內播放。影片編碼能否播放取決於裝置支援，原始檔案仍會保留在備份內。
- 搜尋名稱、筆記內容、附件名稱，並依類型、日期與時間篩選。
- 每日及每週統計；未填寫的熱量或分鐘數以 0 計算。
- 資料存於 SQLite 與 App 私有附件資料夾，不需網路、帳號或全相簿存取權限。

每筆最多 10 個附件、每個上限 500 MB。支援 JPEG、PNG、WebP、GIF、HEIC／HEIF，以及 MP4、WebM、3GP、MOV、MKV；部分格式的預覽或播放取決於 Android 版本與編碼。

## SQLite 與匯入匯出

在「資料」頁選擇「匯出完整備份」，透過系統檔案選取器將 ZIP 儲存至使用者選定的位置。備份可存至手機、外接儲存裝置，或系統檔案供應程式提供的位置。

```text
日常食動-YYYY-MM-DD.zip
├── records.sqlite
└── media/
    ├── <UUID>.jpg
    └── <UUID>.mp4
```

`records.sqlite` 是已關閉連線的獨立 SQLite 快照，不需另帶 WAL／SHM 檔案，可解壓後使用 SQLite 工具查看。`records` 資料表保存筆記與時間，`attachments` 保存檔案名稱、類型、大小及筆記關聯，`metadata` 保存遷移狀態。附件實體位於 `media/`，不以 BLOB 存入資料庫；完整搬移請保留整份 ZIP。

匯入時選擇本 App 匯出的 ZIP，程式會先暫存並檢查資料庫版本、資料限制、附件關聯、檔案清單、大小及 ZIP 路徑，再讓使用者選擇：

- **合併新增**：加入新識別碼的筆記，相同識別碼保留手機上現有的內容；重複匯入不會重複建立筆記。即使兩份備份附件檔名相同，也會重新命名新附件以避免覆蓋。
- **取代全部**：再次確認後，以備份取代現有筆記與附件。原資料在新附件複製與 SQLite 交易成功前保持可用。

匯入失敗會保留原有紀錄。備份上限為 4 GB，SQLite 檔案上限 128 MB，最多 100,000 筆筆記及 100,000 個附件。備份未加密，請妥善保管；解除安裝或清除 App 資料會移除手機內的紀錄與附件，已另存的備份可再次匯入。

升級自 1.0 版時會自動將 SharedPreferences 紀錄遷移至 SQLite，保留原始偏好設定作為遷移來源，並用遷移標記避免重複匯入。舊版沒有時間資訊，畫面顯示「未記錄時間」，不會被特定時間的搜尋誤選；編輯時可補上實際時刻。

熱量由使用者自行輸入。App 不估算營養或提供醫療建議，目前沒有雲端同步或自動備份。

## 開發環境

- JDK 21（雲端環境使用 Temurin 21.0.13+9；程式編譯目標 Java 17）
- Gradle 8.11.1（使用隨附 Wrapper，含發行檔 SHA-256 驗證）
- Android Gradle Plugin 8.9.2、Kotlin 2.1.20
- Android SDK Platform 35、Build Tools 35.0.0、Platform Tools
- 最低支援 Android 8.0 / API 26，目標 API 35

使用 Android Studio 開啟此資料夾，安裝 SDK 35，等待 Gradle 同步。在一般本機環境，透過 Android Studio 設定 SDK，或將 `ANDROID_HOME` 指向已安裝的 SDK。

在此 Codex 雲端環境，每次新的終端工作階段先執行：

```bash
source /workspace/android-env/activate.sh
cd /workspace/eat_and_sport_record
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`activate.sh` 由環境安裝流程保存於 checkout 外，設定可寫入的 SDK、Gradle、Java 快取路徑及平台網路代理。不要將代理或機器路徑寫進專案的 Gradle 設定。

此雲端沙箱可建置 APK 及執行 Robolectric 測試；目前 `adb` 初始化會因 `/home/agent/.android` 不可寫入而失敗，也沒有已配置的模擬器。下列真機安裝命令請在有 Android SDK 與已連接裝置的本機電腦執行。

APK 產出位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 測試與安裝

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moon.eatandsport/.MainActivity
```

測試包含模型及輸入邊界，以及 Robolectric 在 API 26、35 上執行的 SQLite 遷移、交易回復、搜尋、附件複製、ZIP 備份還原、損壞備份保護、系統檔案選取器結果、筆記編輯與畫面狀態重建。Robolectric 不需要 Android 模擬器。

連接真機或自行啟動模擬器後，手動驗證：

1. 進入飲食或運動紀錄，新增有日期、時間與文字的筆記；熱量可留空。
2. 多選一張圖片與一段影片，確認圖片預覽、點開放大及影片播放控制。
3. 編輯、移除附件、取消未儲存的修改，確認既有資料保持正確。
4. 切換每週日期、類型，搜尋關鍵字、日期與時刻，檢查每日統計。
5. 匯出完整 ZIP，確認裡面有 SQLite 與附件；在另一台裝置匯入並確認完整內容。
6. 重複合併同一備份，確認不會產生重複筆記；確認「取代全部」有第二次確認。
7. 在選取附件、編輯或背景儲存期間旋轉螢幕，確認草稿與進度；重新啟動 App 確認資料。
8. 檢查大字體、系統列、鍵盤，以及不同文件供應程式與影片編碼。

雲端環境的自動檢查不代表已完成真機驗證。Debug APK 僅供開發測試；正式上架前需另行設定 release 簽章與發布流程。

## 程式結構

- `MainActivity.kt`：首頁、紀錄時間軸、每週日期、搜尋、統計及資料管理。
- `EditorActivity.kt`：筆記編輯、系統附件選取器、草稿狀態。
- `MediaViewerActivity.kt` / `MediaViews.kt`：取樣圖片預覽與影片播放器。
- `Record.kt` / `Attachment.kt`：資料模型與限制。
- `RecordStore.kt`：SQLite schema、參數化搜尋、交易與舊資料遷移。
- `AppData.kt`：附件複製、編輯儲存與刪除。
- `BackupManager.kt`：可攜式 SQLite 快照、串流 ZIP、匯入驗證及合併／取代。
- `WorkModels.kt`：跨螢幕旋轉保留的背景工作與狀態。
- `app/src/test/`：模型、資料庫、備份及 Android 畫面流程測試。

此專案無背景服務或伺服器。新雲端工作使用既有 checkout，不需要另建 Git worktree。
