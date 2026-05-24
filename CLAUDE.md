# CLAUDE.md — rmssd-h10n

Polar H10 からリアルタイムに **RMSSD（HRV）** を算出する Node.js ツール。Vibe Coding 中の自律神経モニタリング用途。

## クイックリファレンス

```bash
npm install                      # 依存導入（@abandonware/noble, express, ws）
node index.js                    # 常駐モニター（HR-RR、デフォルト、自動再接続）
node index.js --simulate         # ハードウェアなし（合成RR）
node index.js --ecg              # PMD生ECG経路（実験的・後述の理由で不安定）
node tools/measure.js -s 30      # ワンショット計測 → JSONをstdout（生成AI/スクリプト用）
node tools/vitals.js [--json]    # 常駐セッションの現在値を読む
node tools/scan.js               # 周辺BLEデバイス列挙（H10が見えるか確認）
```

- ダッシュボード: `http://localhost:3000`（`--port`変更可、監視運用は3010を使用）
- ステータスAPI: `/api/status`、ファイル: `data/status.json`（毎秒更新）

## アーキテクチャ

```
H10 ──BLE──┬─ (default) HRサービス0x2A37 → RR間隔
            └─ (--ecg)   PMD生ECG130Hz → R波検出(qrs.js)
                                          → rmssd.js(窓RMSSD) ─┬─ server.js (graph/WS/API)
                                                               ├─ csv.js   (data/*.csv)
                                                               └─ statusfile.js (status.json)
```

| ファイル | 役割 |
|---|---|
| `index.js` | CLI・1Hzレポートループ・モード分岐・**自動再接続**・graceful shutdown |
| `src/hrm.js` | HR Measurement(0x2A37) パーサ（HR + RR、RRは1/1024秒単位） |
| `src/pmd.js` | PMD UUID・ECG開始/停止コマンド・ECGフレームパーサ（`--ecg`用） |
| `src/qrs.js` | ストリーミングR波検出（簡略Pan-Tompkins、`--ecg`用） |
| `src/rmssd.js` | スライディング窓 RMSSD/SDNN/HR・**local-median+dRRアーティファクト除去**・RMSSDのEMA平滑値（`compute(nowMs)`でstale退避） |
| `src/analysis.js` | `Baseline`（安静ゲート付き中央値・JSON永続化）+ `StateClassifier`（lnRMSSD差分・HRデッドバンド・ヒステリシス）・覚醒度0-100 |
| `src/respiration.js` | RSA呼吸数推定（RR→4Hz補間→2次detrend→**Welch PSD**→探索帯0.10-0.50Hzのピーク）。confidenceは**SNR×ピーク鋭さ**の信号品質。`node src/respiration.js`で自己テスト |
| `src/ble.js` | noble スキャン/接続/characteristic取得・`disconnectWithTimeout` |
| `src/server.js` | express静的 + WebSocket + `/api/status` + `POST /api/baseline/reset`（`events`で通知、close時にWSも閉じる） |
| `src/time.js` | `localIso()` = JSTオフセット付きISO（`+09:00`）。**全タイムスタンプはこれを使う** |
| `tools/measure.js` | ワンショット計測CLI。stdout=JSONのみ/stderr=ログ。watchdogで必ず終了 |
| `tools/vitals.js` | status.json or `/api/status` を読む（`--json`/`--watch`対応） |
| `tools/{scan,hrdump,ecgdump,shutdown-test}.js` | 診断用 |

## 重要な前提・ハマりどころ（このセッションで判明）

1. **PMD生ECGはWindows(WinRT)+nobleで不安定**。コントロールポイントのread/write/indicateが `communication status: 2`(ProtocolError) になりECG 0フレーム。**デフォルトのHR-RR経路を使うこと**。RMSSDにはRRで十分（R波検出はH10ファーム側＝高品質）。`--ecg`は実験用。
2. **H10のPMD/ECGは単一接続専用**。スマホのPolar Flow/Beatや他アプリが掴んでいるとHR/RRは取れてもECGは流れない。
3. **Windowsでペアリング不要**。nobleが直接GATT接続する。OS設定でペアリング/接続すると逆に干渉しうる。
4. **force-killは厳禁**。`Stop-Process -Force`等で強制終了するとWindowsがBLE接続を掴んだまま残り（orphaned）、H10が広告停止→次のスキャンで見つからない/`discoverHr`がハング。**正常停止はターミナルで Ctrl+C**（`disconnectAsync`が走る）。force-killしてしまったらH10をストラップから外す or Bluetooth OFF→ONで復帰。
5. **自動再接続あり**: HR-RR経路はドロップしても再スキャン→再接続を5s間隔でリトライ。discover/subscribeにもタイムアウト（10s/8s）があり、ハングしたら切断して再試行する。
6. **タイムスタンプは全てJST**（`src/time.js`の`localIso()`）。`new Date().toISOString()`(UTC/Z)は使わない。
7. **scanはallowDuplicates=true必須**。H10は名前なし/Polar名つきの広告を交互に出すため、falseだと名前マッチに引っかからない（`src/ble.js`）。

## プロセス管理（Windows）

```bash
# 監視を常駐起動（run_in_background）
node index.js --port 3010

# 動いているか / ポート占有を確認
pwsh -NoProfile -Command "Get-NetTCPConnection -State Listen | Where-Object {$_.LocalPort -eq 3010} | Select LocalPort,OwningProcess"

# 停止（Ctrl+Cが使えない常駐は最終手段でStop-Process。orphan化に注意）
pwsh -NoProfile -Command "Get-NetTCPConnection -State Listen | Where-Object {$_.LocalPort -eq 3010} | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"
```

## コード規約

- CommonJS（`require`）。Node >= 18。
- 既存の簡潔なスタイルに合わせる。各モジュールは単一責務。
- 新しいタイムスタンプは必ず `localIso()`。BLE操作はハング前提でタイムアウト/フォールバックを付ける。
- `data/` は実行時出力（gitignore対象、`.gitkeep`のみ追跡）。

## 解析・ダッシュボード

- **baseline**: 接続後 約60サンプル(≈1分)で RMSSD(EMA平滑値)/HR の中央値を確定。**安静ゲート**で直近HR中央値から大きく外れる読み（装着直後/会話/動作の過渡）は採用しない。ダッシュボードの「**安静で基準を取り直す**」(`POST /api/baseline/reset`)で再キャリブ。確定baselineは`data/baseline.json`に保存、`--load-baseline`で24h以内のものを再利用。
- **状態(気分)**: `StateClassifier`が **lnRMSSD差分 + HRデッドバンド** で分類し、**45sのヒステリシス**でラベルのバタつきを抑制。区分: リラックス・回復 / 回復傾向 / 平常・安定 / 集中 / ストレス・緊張↑ / 高負荷・興奮。状態判定にはRMSSDの**EMA平滑値**を使う。**HRV推定であって診断ではない**。
- **呼吸数**: RSA + **Welch PSD**（60s窓50%オーバーラップ平均）。探索帯0.10–0.50Hz。confidenceは `peak/ノイズフロア(中央値)` のSNRとピーク鋭さの合成＝**信号品質**（確率ではない）。約30–60秒は`preview`（暫定）、60秒以上で正規。直近5推定の中央値で時間平滑。低RMSSD(弱RSA)では「正しく低品質」になり得る。
- **タイムライン目安**: 計測待ち解消 約1–2秒 / 呼吸数 暫定 約30秒・正規 約60秒 / baseline確定(状態表示) 約60秒。
- **長期トレンド**: ダッシュボード下部に **15分平均**のRMSSD/HR/呼吸グラフ。詳細履歴とは別キー `rmssd-h10n.trend.v1`（最大1500区間≒約15日）でlocalStorage永続化し、日をまたいで蓄積。読込時は詳細履歴から過去バケットを再構築し、以降はライブ点を現在バケットに加算→15分境界で確定。
- **状態カラー帯**: WS `point` に `tone` を乗せ、Chart.jsプラグイン（`stateBandPlugin`）で両グラフ背景を状態色で塗る。トレンドはバケット最多トーン。過去データ（tone未記録）は `toneFromVitals()`（クライアント版classifyRaw）でRMSSD/HRから遡って再計算（現baseline適用・ヒステリシス無し）。`refreshBands()`はbaseline確定時に発火。
- **UI規約**: 絵文字は使わない（色インジケータ＋テキストで表現）。ダッシュボードの詳細履歴は **localStorage** に保存（`rmssd-h10n.history.v1`、最大約3600点）、「履歴をクリア」で詳細・長期トレンド両方を消去。

## Android アプリ（Capacitor）＋ H10オフライン穴埋め

`app/` が Capacitor アプリ。ネイティブ常駐で計測し、離席/圏外/アプリkill/端末再起動で BLE 切断中も **H10本体メモリの RR 記録**から復元して時間連続にする。

```
app/android/.../MonitorService.java   前面サービス（エンジン所有・START_STICKY・user-stop⇔OS-kill区別）
                HrvEngine.java         1Hz計算ループ（RMSSD/姿勢/呼吸/baseline/state）＋RecordingStore実装＋穴埋め永続化
                PolarBle.java          Polar BLE SDK 6.16.1(Java/RxJava3) 駆動。ライブHR/RR/ACC＋録音状態機械
                HrvDb.java             SQLite=真実の源。points/status_latest/kv/recordings/backfill_imports
                HrvNativePlugin.java   WebViewブリッジ（live push＋getPointsSince/getUnmergedImports catch-up）
                BootReceiver.java      BOOT_COMPLETEDで監視を再開（kv engine==native時）
app/src/app.js + app/www/index.html    ダッシュボード（esbuild: src→www/app.js、index.htmlはwww直）
```

### ビルド／配信
- `cd app && npm run build`（esbuild: `src/app.js`→`www/app.js`）→ `npx cap copy android` → `cd app/android && ./gradlew :app:assembleDebug`。
- ユニット: `./gradlew :app:testDebugUnitTest`。コンパイルのみ: `:app:compileDebugJavaWithJavac`。
- WiFi越しadb: USB接続中に `adb tcpip 5555` → IP取得 → `adb connect <phone-ip>:5555`（USB抜いてもWiFi維持）。

### Polar BLE SDK の前提（重要）
1. **`FEATURE_POLAR_DEVICE_TIME_SETUP` は有効化しない**。H10は時刻READ非対応で、SDKの feature-check probe が10sハング→**全ストリーム(HR含む)が落ちる**。穴埋めは自前クロックの start-anchor なのでデバイス時刻は不要。有効feature= HR / ONLINE_STREAMING / H10_EXERCISE_RECORDING / DEVICE_INFO。
2. **H10はOSボンディング必須**（PFTP=録音転送は暗号化リンク必須）。SDK接続前に `ensureBonded()` で `createBond()`。ライブHR/ACCはボンディング不要だが録音取得はPFTP=要ボンド。
3. **PFTP 106 (OPERATION_NOT_PERMITTED)**: 接続直後に録音操作を走らせるとストリーム確立と競合して106。対策=**接続後8秒ディレイ＋リトライ**、かつ **start前に必ずスロットを空ける**（stop→ours削除）。**録音中/非空スロットへ startRecording すると106**。
4. **ACCは `requestStreamSettings` で実機が出す組合せから選ぶ**（25Hz/16bit/2G優先）。ハードコードは拒否され姿勢が取れない。
5. **再接続/teardownは自前監督**＋force-kill厳禁。`am force-stop` を**スキャン中に繰り返す**とAndroid BLEスキャナがwedge→端末再起動で解消。接続中の単発force-stopは比較的安全。

### オフライン穴埋め（再起動を跨ぐ復元）
- H10本体= **単一スロット**。RR記録で **約95,000拍 ≒ 約20時間**、満杯で自動停止（上書きせず）。BLE切断中も記録継続。`PolarExerciseEntry.date`は信用不可(issue#168)→**自前クロックの start-anchor**で逆算せず前進再生。
- **録音状態機械**（`recordings`テーブルに永続化）: `starting`→`active`→`fetching`→`persisted`→`removed` / `discarded_by_user`。**メタがDBに残るのでアプリ/OS再起動を跨いで復元**（在メモリだけに持たない）。`startRecording` 発行**前**に `starting`＋start-anchor を commit。
- **接続時フロー**: `getOpenRecording`(DB) → 一致する on-device exercise を **exId↔identifier** で照合 → stop → fetch → `Backfill.replay`(start-anchor前進再生) → **`backfillCommit`（1トランザクションで `INSERT OR IGNORE` points ＋ `backfill_imports` ledger）** → 永続確認後に remove → 新規 start。**fetch失敗時は新規 startしない**（単一スロット上書きで未取得ギャップを失うため）→リトライ。
- **dedup/冪等**: 既存秒は `pointTimesIn` でスキップ＋`INSERT OR IGNORE`（liveの境界秒をnull姿勢で壊さない）。同一anchorは同一秒を再構築するので remove失敗→再取得しても二重化しない。
- **user-stop ⇔ OS-kill**: 明示停止(`stopEngine`)は `markUserStopped()`→`discarded_by_user`で**復元しない**。OS-kill(`onDestroy`でengine!=null)は `active`のまま→次回起動で復元。
- **UIへの反映はイベント非依存**: `backfill_imports` ledger を起動/復帰/イベントで drain（`getUnmergedImports`→`nativeBackfillMerge`→`__mergeBackfill`＝history再構築＋trend `replaceBuckets`）。WebView未アタッチ中のサービス単独復元も次回ロードで反映。「**離席分を復元しました（約N分・一部欠落）**」。
- **満杯(truncated)**: 録音が今より大きく前に自動停止＋RR上限/~18h で検知→復元範囲を `[start,start+duration]` に限定し `truncated` をledger/UIに明示。
- DBは **schema v2**。`onUpgrade` は**追加のみ（pointsを消さない）**。

## トレンド分析

`data/rmssd-u<N>-*.csv`（列: `user,wallClock,tMs,rr_ms,rmssd_ms,sdnn_ms,hr_bpm,rrCount,resp_brpm,resp_conf,corrected,state`）を解析。`corrected`はアーティファクト除去拍の累積。HR/RMSSDの min/avg/max と直近窓 vs 過去の差分で傾向を見る。安静時RMSSDの目安は20–50ms（高HR時は低下＝交感神経優位）。
