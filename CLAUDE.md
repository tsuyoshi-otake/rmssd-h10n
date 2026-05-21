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
| `src/rmssd.js` | スライディング窓 RMSSD/SDNN/HR・アーティファクト除去（`compute(nowMs)`でstale退避） |
| `src/ble.js` | noble スキャン/接続/characteristic取得・`disconnectWithTimeout` |
| `src/server.js` | express静的 + WebSocket + `/api/status`（close時にWSも閉じる） |
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

## トレンド分析

`data/rmssd-*.csv`（列: `wallClock,tMs,rr_ms,rmssd_ms,sdnn_ms,hr_bpm,rrCount`）を解析。HR/RMSSDの min/avg/max と直近窓 vs 過去の差分で傾向を見る。安静時RMSSDの目安は20–50ms（高HR時は低下＝交感神経優位）。
