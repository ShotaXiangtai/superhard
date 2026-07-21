# SuperHard

Hard SMP チャレンジプラグイン for **Paper 26.1.2**

真のクラフターモード にインスパイアされた独自設計のプラグイン。
バニラより遥かに難しく、仲間と協力しないと生き残れない。

## ダウンロード

→ **[Releases](../../releases/latest)** から最新 JAR をダウンロード

## 要件

| 項目 | バージョン |
|------|-----------|
| Minecraft / Paper | 26.1.2 build 53+ |
| Java | 21 以上 |
| AuraSkills | 任意（入れると連携機能が有効） |

## インストール

1. `superhard-*.jar` を `plugins/` フォルダに置く
2. サーバーを起動（または `/reload`）
3. `plugins/SuperHard/config.yml` で各種設定を調整

## 主な機能

### RAGE レベル（Lv.1〜5）
倒した数・生存時間で上がる脅威スコア。レベルが高いほどモブが強くなる。

### 精鋭モブ（Lv.1 / Lv.2 / Lv.3）
通常モブの一部がランダムで強化される。倒すと **鋼** をドロップ。

### 強化モブ AI
- ゾンビ: 跳躍・ブロック破壊・足場構築・仲間召喚
- スケルトン: ストレイフ・武器切替・バックステップ
- クリーパー: 透明接近・炎の残留
- スパイダー: 蜘蛛の巣・スライム弾
- ウィッチ: モブバフオーラ
- エンダーマン: 自動アグロ・水の凍結・ヴェックス召喚

### レイド
ランダム深夜に発動するウェーブ制のモブ攻撃。近隣の拠点を一斉に狙う。

### レイドボス
毎日 1〜2 回自動スポーン。複数人で挑まないと Phase III で HP が回復する仕組み。
ログイン時に次回降臨カウントダウンを表示。

### 鋼 → [TEMPERED] 装備
精鋭モブのドロップ素材をアンビルで装備に組み合わせると Unbreaking III 付きの強化装備に変化。

### AuraSkills 連携（任意）
スキルレベルに応じて周囲モブが強化。精鋭・ボス撃破で大量 XP ボーナス。

## プレイヤー向けガイド

→ **[GUIDE.md](GUIDE.md)**

## 設定

`plugins/SuperHard/config.yml` で以下を調整可能:
- RAGE スコアの増減量
- 精鋭出現率
- モブ HP 倍率
- レイドボスのスポーン間隔（デフォルト: 12〜24h）
- 包囲レイドの Wave 設定

## 管理者コマンド

```
/sh status                     状態確認
/sh threat [player]            RAGE スコア確認
/sh setlevel <player> <0-9999> RAGE を強制設定
/sh elite [SHURA|HASHA|TENMA]  視線先のモブを精鋭化
/sh boss spawn                 レイドボスを手動スポーン
/sh reload                     コンフィグ再読み込み
```

権限: `superhard.admin`（デフォルト: op のみ）

## ライセンス

[GNU General Public License v3.0](LICENSE)

## ビルド

```bash
mvn package
# target/superhard-*.jar が生成される
```
