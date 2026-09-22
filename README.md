# Claude アシスタント（Android）

Claude API を直接叩く、自分専用のデジタルアシスタント。
Galaxy のサイドキー長押しで Gemini の代わりに呼び出し、今の画面の上に重ねて表示する。

- Gradle・AndroidX・外部ライブラリ不使用。`build.sh` が aapt2 / javac / d8 / apksigner を直接呼ぶ
- push すると GitHub Actions がビルドし、`Assistant.apk` と `latest.json` をリポジトリに戻す
- アプリ内「更新確認」で `latest.json` を見て更新
- APIキーは端末内で Android Keystore により暗号化保存。ソースには含まれない

署名鍵は Secret `RELEASE_KEYSTORE_B64` / `KS_PASS` から復元する（リポジトリには入れない）。
