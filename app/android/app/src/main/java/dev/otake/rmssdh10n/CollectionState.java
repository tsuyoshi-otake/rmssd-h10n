package dev.otake.rmssdh10n;

/** User-visible collection state must describe fresh data, not the service process. */
enum CollectionState {
    WAITING("H10未接続 — 装着とBluetoothを確認してください"),
    WARMING("H10接続済み — 心拍データを待っています"),
    STALLED("H10が無反応 — センサーとBluetoothを確認してください"),
    PAIRING("H10のペア設定が必要 — アプリを開くと許可を求めます"),
    RECEIVING("心拍変動を計測中（バックグラウンド）");

    final String text;
    CollectionState(String text) { this.text = text; }

    /** {@code unbonded}: the phone holds no bond for the H10, so a silent link may be waiting
     *  on a pairing only the user can accept (#30) rather than on a wedged sensor. */
    static CollectionState from(boolean connected, boolean fresh, boolean stalled, boolean unbonded) {
        if (!connected) return WAITING;
        if (fresh) return RECEIVING;
        if (!stalled) return WARMING;
        return unbonded ? PAIRING : STALLED;
    }
}
