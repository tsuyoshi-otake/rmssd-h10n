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
- `src/rmssd.js` — 時間窓 RMSSD / SDNN / HR、local-median + dRR アーティファクト除去、RMSSD の EMA 平滑値
- `src/analysis.js` — baseline（安静ゲート付き・JSON 永続化）+ 自律神経状態（lnRMSSD + ヒステリシス）推定
- `src/respiration.js` — RSA による呼吸数推定（RR→4Hz補間→2次detrend→Welch PSD→探索帯ピーク、SNRベース信号品質）
- `src/ble.js` — noble による H10 スキャン・接続・characteristic 取得・タイムアウト付き切断
- `src/server.js` — express 静的配信 + WebSocket + `/api/status` + `POST /api/baseline/reset`
- `src/time.js` — JST オフセット付き ISO タイムスタンプ（`localIso()`）
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

# データが溜まるにつれ、全履歴の安静クラスタから基準値を自動で取り直す
node index.js --auto-baseline                 # 既定15分間隔
node index.js --auto-baseline --auto-baseline-interval 10

# ユーザー1〜5を選んで開始（基準値・CSV・グラフ履歴は個別に保存）
node index.js --user 3
```

- **ユーザー切り替え**: ダッシュボード右上の `ユーザー 1〜5` ボタン、または `POST /api/user {"user":N}` で計測対象を切替。切替時はその人の安静基準（`data/baseline-u<N>.json`、24h以内なら）を再利用し、計測窓・分類器・CSVをリセットして新規セッションを開始する。各人の基準値・CSV(`data/rmssd-u<N>-*.csv`)・ダッシュボードのグラフ履歴(localStorage)は完全に独立。
- **自動リベースライン** (`--auto-baseline`): 初回確定後も全履歴の「安静クラスタ（HR下位25%）」から RMSSD/HR の中央値を一定間隔で再算出し、EMA(±20%上限)で基準を緩やかに追従させる。安静らしい区間が足りない時間帯は更新しない。

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
  "rmssdSmoothed": 4.6,
  "corrected": 2,
  "baseline": { "rmssd": 41.2, "hr": 62.0 },
  "calibration": 1,
  "state": { "label": "集中", "tone": "focus", "arousal": 64, "detail": "軽い覚醒。タスクに没頭しているフロー寄りの状態。" },
  "respiration": 15.2,
  "respirationConfidence": 0.71,
  "respirationPreview": false,
  "updatedAt": "2026-05-21T20:44:48.000+09:00"
}
```

> タイムスタンプ（`updatedAt`・CSVの`wallClock`）は **JST オフセット付き ISO-8601**（`+09:00`）で記録される。

## 解析（baseline・状態推定・呼吸数）

- **baseline（基準値）**: 接続後の最初の約60サンプル（≈1分）の RMSSD(EMA平滑値)/HR の中央値を確定（`src/analysis.js`）。**安静ゲート**により直近HR中央値から大きく外れる読み（装着直後・会話・動作の過渡）は採用せず、安静寄りの基準にする。`calibration` は 0→1 の確定進捗。確定した baseline は `data/baseline.json` に保存され、`--load-baseline` で24h以内のものを再利用できる。ダッシュボードの「**安静で基準を取り直す**」ボタン（`POST /api/baseline/reset`）でいつでも再キャリブレーション可能。
- **状態（気分）推定**: HR と RMSSD の基準値からの **lnRMSSD 差分 + HR 差分**（デッドバンド付き）で自律神経の状態を分類（`state.label` / `state.tone` / `arousal` 0–100）。区分: リラックス・回復 / 回復傾向 / 平常・安定 / 集中 / ストレス・緊張↑ / 高負荷・興奮。**45秒のヒステリシス**でラベルのバタつきを抑制し、判定にはRMSSDのEMA平滑値を使う。**心拍変動からの推定であり医療・心理診断ではない**。
- **呼吸数（RSA）**: RR間隔の揺らぎ（呼吸性洞性不整脈）を **Welch PSD**（60秒窓・50%オーバーラップ平均）で解析し、探索帯 0.10–0.50Hz（6–30回/分）のスペクトルピークから推定（`src/respiration.js`）。`respirationConfidence` は `ピーク/ノイズフロア(中央値)` の SNR とピーク鋭さを合成した **信号品質**（確率ではない）。約30–60秒は `respirationPreview=true`（暫定値）、60秒以上で正規値。直近の推定を中央値で時間平滑する。低RMSSD（弱RSA）では信号品質が低く出るのが正しい挙動。

### タイムライン（装着・起動後の目安）
| 段階 | 目安 |
|---|---|
| RMSSD/HR 表示開始（計測待ち解消） | 接続後 約1–2秒 |
| 呼吸数 暫定表示（preview） | 約30秒 |
| 呼吸数 正規表示 | 約60秒 |
| baseline 確定（状態・基準比が出る） | 約60秒 |

## ダッシュボードの機能

- 状態カード（色インジケータ＋ラベル＋覚醒度バー）、RMSSD/HR の基準比 ▲▼、呼吸数（信号品質%・暫定表示）、SDNN、RR窓内、RMSSD/HR/呼吸の二軸＋補助軸リアルタイムグラフ。
- **長期トレンド（15分平均）グラフ**: RMSSD/HR/呼吸の15分平均を別グラフで表示。詳細グラフ（1秒間隔・約1時間）とは独立に **localStorage に永続化**（`rmssd-h10n.trend.v1`、最大1500区間≒約15日）され、日をまたいで蓄積する。
- **状態（気分）カラー帯**: 両グラフの背景を状態のトーンで塗り分け（緑=リラックス/回復・青=平常・水色=集中・橙=緊張・赤=高負荷）。WSの各点に状態トーンを乗せて描画する。機能追加前に記録された過去データは、保存済みの RMSSD/HR を baseline と照合して**状態を遡って再計算**し帯を復元する（現在のbaselineを当てはめた近似で、ヒステリシスは未適用）。
- **「安静で基準を取り直す」ボタン**で、今を安静としてbaselineを再計測。
- **履歴は localStorage に保存**（詳細 `rmssd-h10n.history.v1` 最大約3600点／1時間相当 + 長期トレンド）。再読み込みしても復元される。
- **「履歴をクリア」ボタン**で詳細・長期トレンド両方の保存履歴を消去できる（CSV/サーバ側の記録には影響しない）。

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

`user, wallClock, tMs, rr_ms, rmssd_ms, sdnn_ms, hr_bpm, rrCount, resp_brpm, resp_conf, corrected, state`

（`resp_conf` = 呼吸数の信号品質、`corrected` = アーティファクト除去拍の累積）

## 既知の問題・トラブルシュート

- **PMD 生ECG (`--ecg`) が Windows で 0 フレーム**: `@abandonware/noble` の WinRT バックエンドが PMD コントロールポイントとうまく通信できないことがある（`communication status: 2` / ProtocolError）。また Polar 公式によると **H10 の PMD/ECG は単一接続専用**で、別アプリが PMD を掴んでいると HR/RR は取れても ECG は流れない。まずデフォルトの HR-RR 経路を推奨。
- **スキャンで H10 が見つからない**: 直前のプロセスを強制終了(force-kill)すると Windows が BLE 接続を掴んだまま残り、H10 が広告を停止することがある。H10 をストラップから一度外す、または Windows の Bluetooth を OFF→ON で復旧。通常は Ctrl+C で正常終了すれば起きない。
- **RR が届かない**: 電極を湿らせ、ストラップを密着させる。装着直後は数秒かかる。
- **RMSSD が極端に低い**: HR が高い（運動・緊張）と HRV は低下する。安静時で 20〜50ms 程度が目安。

## メモ

- 標準 HR サービスの RR-interval は 1/1024 秒単位（`(uint16 / 1024) * 1000` で ms 換算）。
- RMSSD はデフォルト 30 秒窓。短時間 HRV では 30〜60 秒が一般的。
- `--ecg` の R 波検出は簡略 Pan-Tompkins。臨床用途ではなくセルフトラッキング向け。
