package dev.otake.rmssdh10n;

/** User-visible collection state must describe fresh data, not the service process. */
enum CollectionState {
    WAITING("H10未接続 — 装着とBluetoothを確認してください"),
    WARMING("H10接続済み — 心拍データを待っています"),
    STALLED("H10が無反応 — センサーとBluetoothを確認してください"),
    RECEIVING("心拍変動を計測中（バックグラウンド）");

    final String text;
    CollectionState(String text) { this.text = text; }

    static CollectionState from(boolean connected, boolean fresh, boolean stalled) {
        if (!connected) return WAITING;
        if (fresh) return RECEIVING;
        return stalled ? STALLED : WARMING;
    }
}
