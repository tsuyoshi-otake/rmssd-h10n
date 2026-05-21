# rmssd-h10n

Polar H10 から心拍変動指標 **RMSSD** をリアルタイム算出する Node.js ツール。ライブグラフ表示と CSV ロギングの両方に対応し、Claude Code やシェルからも現在のバイタルを直接観測できる。

デフォルトは**標準心拍サービス(0x2A37)の RR-interval** から RMSSD を計算する（Windows で安定動作し、R 波検出は H10 ファームウェアが行うため高品質）。Polar PMD の**生 ECG ストリーム**は `--ecg` で利用できるが実験的（後述の既知の問題を参照）。

## 仕組み

```
                    ┌─ (default) 標準HRサービス 0x2A37 ─▶ RR間隔(1/1024s)
Polar H10 ──BLE──┤
                    └─ (--ecg)   PMD 生ECG 130Hz ─▶ R波検出 ─▶ RR間隔
                                                                  │
                          ┌────────────────────────────────────────┘
                          ▼
                  スライディング窓 RMSSD ──┬──▶ Web ダッシュボード (Chart.js, WebSocket)
                                          ├──▶ CSV ログ (data/*.csv)
                                          └──▶ status.json + /api/status (外部観測用)
```

- `src/hrm.js` — Heart Rate Measurement(0x2A37) パーサ（HR + RR-interval）
- `src/pmd.js` — PMD サービス UUID、ECG 開始コマンド、ECG フレームパーサ（`--ecg` 用）
- `src/qrs.js` — ストリーミング R 波検出（簡略 Pan-Tompkins, `--ecg` 用）
- `src/rmssd.js` — 時間窓 RMSSD / SDNN / HR、アーティファクト除去
- `src/ble.js` — noble による H10 スキャン・接続・characteristic 取得
- `src/server.js` — express 静的配信 + WebSocket + `/api/status`
- `src/csv.js` / `src/statusfile.js` — CSV ロガー / status.json アトミック書き込み
- `index.js` — 全体統合・CLI・1Hz レポートループ
- `tools/` — 診断・観測用の補助スクリプト（下記）

## セットアップ

```bash
npm install
```

> Windows では `@abandonware/noble` が WinRT 経由で BLE を使用します。Bluetooth が ON であること、H10 を他アプリ（Polar Flow / Polar Beat、スマホ等）が掴んでいないことを確認してください。**H10 を Windows 設定でペアリングする必要はありません**（noble が直接 GATT 接続します）。

## 実行

```bash
# デフォルト: 標準HRサービスのRRからRMSSD（Windowsで安定）
node index.js

# ハードウェアなしで動作確認（合成RRデータ）
node index.js --simulate

# 実験的: PMD 生ECG + 自前R波検出
node index.js --ecg

# 主なオプション
node index.js --window 60 --port 3000 --csv data/run.csv
```

- ダッシュボード: <http://localhost:3000>
- ステータス API: <http://localhost:3000/api/status>
- 停止: ターミナルで **Ctrl+C**（`disconnectAsync` が走り BLE 接続を綺麗に解放する）
- **自動再接続**: HR-RR 経路は H10 が接続をドロップしても再スキャン→再接続を自動でリトライ（discover/subscribe にもタイムアウトあり）。長時間モニタリングに対応。

## Claude Code / シェルからのバイタル観測

実行中プロセスは `data/status.json` を 1 秒ごとに更新し、HTTP でも同じ値を公開します。

```bash
node tools/vitals.js                                  # 現在値を1回表示（ファイル経由）
node tools/vitals.js --watch                          # 毎秒更新で表示
node tools/vitals.js --url http://localhost:3000/api/status   # HTTP経由
curl http://localhost:3000/api/status                 # 生JSON
cat data/status.json
```

`status.json` の例:

```json
{
  "connected": true,
  "mode": "hr-rr",
  "hr": 98.0,
  "rmssd": 4.2,
  "sdnn": 5.1,
  "rrCount": 23,
  "beatsTotal": 23,
  "rejected": 0,
  "updatedAt": "2026-05-21T20:44:48.000+09:00"
}
```

> タイムスタンプ（`updatedAt`・CSVの`wallClock`）は **JST オフセット付き ISO-8601**（`+09:00`）で記録される。

## CLI ワンショット計測（生成AI / プログラム連携向け）

サーバを立てずに、**1コマンドで接続→計測→JSON出力→切断**する自己完結CLI。
結果 JSON は **STDOUT のみ**、進捗ログは **STDERR** に出るので、そのままパイプで LLM やスクリプトに渡せる。

```bash
node tools/measure.js                 # 30秒計測、JSONをstdoutへ
node tools/measure.js --seconds 60    # 計測時間を指定
node tools/measure.js --rr            # 生RR配列も含める
node tools/measure.js --ecg           # PMD生ECG経路を使う（実験的）
node tools/measure.js --pretty        # 整形JSON

# 例: RMSSDだけ取り出す
node tools/measure.js -s 20 | jq .metrics.rmssd_ms
npm run measure -- --seconds 30
```

出力スキーマ:

```json
{
  "ok": true,
  "device": "Polar H10 1B54C836",
  "mode": "hr-rr",
  "startedAt": "2026-05-21T12:04:09.986Z",
  "finishedAt": "2026-05-21T12:04:21.986Z",
  "durationSec": 12,
  "samples": { "rrAccepted": 21, "rrRejected": 0, "beatsTotal": 21 },
  "metrics": {
    "count": 21, "rmssd_ms": 4.1, "sdnn_ms": 7.5, "hr_bpm": 103.8,
    "meanRr_ms": 578, "minRr_ms": 563, "maxRr_ms": 587
  },
  "rr_ms": [563, 563, 566, ...]
}
```

終了コード: `0`=成功 / `1`=データ不足 / `2`=接続・致命的エラー（いずれも `ok` と `error` を JSON で返す）。

実行中の常駐セッション（`node index.js`）から現在値を読むには `vitals` を使う（こちらも `--json` 対応）:

```bash
node tools/vitals.js --json           # status.json を1行JSONで
node tools/vitals.js --url http://localhost:3000/api/status --json
```

## 診断ツール (`tools/`)

```bash
node tools/scan.js [ms]       # 周辺のBLEデバイスを列挙（H10が見えるか確認）
node tools/hrdump.js [ms]     # HRサービスのHR+RRを生表示（~12秒で自動終了）
node tools/ecgdump.js [ms]    # PMD ECGの生フレーム/制御応答をダンプ（--ecg調査用）
node tools/shutdown-test.js   # server.close()がWS接続中でも解決するか検証
```

## CSV 列

`wallClock, tMs, rr_ms, rmssd_ms, sdnn_ms, hr_bpm, rrCount`

## 既知の問題・トラブルシュート

- **PMD 生ECG (`--ecg`) が Windows で 0 フレーム**: `@abandonware/noble` の WinRT バックエンドが PMD コントロールポイントとうまく通信できないことがある（`communication status: 2` / ProtocolError）。また Polar 公式によると **H10 の PMD/ECG は単一接続専用**で、別アプリが PMD を掴んでいると HR/RR は取れても ECG は流れない。まずデフォルトの HR-RR 経路を推奨。
- **スキャンで H10 が見つからない**: 直前のプロセスを強制終了(force-kill)すると Windows が BLE 接続を掴んだまま残り、H10 が広告を停止することがある。H10 をストラップから一度外す、または Windows の Bluetooth を OFF→ON で復旧。通常は Ctrl+C で正常終了すれば起きない。
- **RR が届かない**: 電極を湿らせ、ストラップを密着させる。装着直後は数秒かかる。
- **RMSSD が極端に低い**: HR が高い（運動・緊張）と HRV は低下する。安静時で 20〜50ms 程度が目安。

## メモ

- 標準 HR サービスの RR-interval は 1/1024 秒単位（`(uint16 / 1024) * 1000` で ms 換算）。
- RMSSD はデフォルト 30 秒窓。短時間 HRV では 30〜60 秒が一般的。
- `--ecg` の R 波検出は簡略 Pan-Tompkins。臨床用途ではなくセルフトラッキング向け。
