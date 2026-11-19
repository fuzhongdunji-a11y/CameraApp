"""Weights & Biases（W&B）に記録しながら分類モデルを追加学習するスクリプト。

2025/学習用/trainnig.py と同じ2段階の学習（判定の層だけ → DenseNet121 の後半も）を行い、
次の内容を W&B に記録する。

- エポックごとの loss / accuracy / val_loss / val_accuracy（学習曲線）
- 学習後の検証データでの 正解率・マクロ平均F1・「健康 / それ以外」の2値の適合率・再現率・F1
- 混同行列、クラス別の適合率・再現率・F1 の表
- 間違えた画像の一覧（元画像と Grad-CAM のヒートマップ、予測と確率）
- 学習済みモデル（.h5、--export_tflite を付けると .tflite も）

使い方（例。CameraApp フォルダで実行）:
    pip install wandb
    wandb login            # 初回だけ。W&B のサイトの API キーを貼り付ける

    python tools/train_wandb.py ^
        --base_model "C:\\...\\Models\\.h5\\pepper_model.h5" ^
        --train "C:\\Users\\fuzho\\Downloads\\archive (2)\\train\\Pepper" ^
        --valid "C:\\Users\\fuzho\\Downloads\\archive (2)\\valid\\Pepper" ^
        --crop Pepper --augment light --export_tflite

    # 動作確認だけしたいとき（各エポック 5 バッチだけ学習）
    python tools/train_wandb.py ... --epochs_head 1 --epochs_finetune 1 --steps_per_epoch 5

ハイパーパラメータの探索（Sweep）は tools/sweep_pepper.yaml を参照。

画像は 0〜255 のままモデルに渡す（モデル内部に Rescaling(1/255) があるため。アプリと同じ）。
データ拡張（--augment）は学習データの読み込み時にだけかけるので、モデルの中には入らず、
書き出した tflite はアプリでそのまま使える。

必要なライブラリ: tensorflow, wandb, numpy, pillow, matplotlib
"""
import argparse
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def parse_args():
    ap = argparse.ArgumentParser(description="W&B に記録しながら分類モデルを追加学習する")
    ap.add_argument("--base_model", required=True, help="追加学習の元にする Keras モデル（.h5）")
    ap.add_argument("--train", required=True, help="学習データのフォルダ（この下にクラスごとのフォルダ）")
    ap.add_argument("--valid", required=True, help="検証データのフォルダ（この下にクラスごとのフォルダ）")
    ap.add_argument("--test", default=None,
                    help="テストデータのフォルダ（任意）。学習中に一度も使わない最終評価用。圃場で撮った画像など")
    ap.add_argument("--crop", default="Pepper", help="作物名（W&B の run 名とファイル名に使う）")
    ap.add_argument("--project", default="cameraapp-plant", help="W&B のプロジェクト名")
    ap.add_argument("--run_name", default=None, help="W&B の run 名。省略すると自動")
    ap.add_argument("--img_size", type=int, default=256)
    ap.add_argument("--batch_size", type=int, default=32)
    ap.add_argument("--epochs_head", type=int, default=5, help="1段階目（判定の層だけ）のエポック数")
    ap.add_argument("--epochs_finetune", type=int, default=10, help="2段階目（DenseNet121 の後半も）のエポック数")
    ap.add_argument("--lr_head", type=float, default=1e-3)
    ap.add_argument("--lr_finetune", type=float, default=1e-5)
    ap.add_argument("--finetune_layers", type=int, default=30,
                    help="2段階目で学習させる DenseNet121 の後ろからの層数。0 なら2段階目も判定の層だけ")
    ap.add_argument("--augment", choices=["none", "light", "strong"], default="none",
                    help="学習データの拡張。light: 反転・回転、strong: さらに拡大縮小・明るさ・コントラスト")
    ap.add_argument("--patience", type=int, default=0,
                    help="val_loss が改善しないエポックがこの数続いたら、その段階を早めに打ち切る。0 で無効")
    ap.add_argument("--steps_per_epoch", type=int, default=0, help="1エポックで使うバッチ数の上限（動作確認用）。0 で全部")
    ap.add_argument("--log_wrong", type=int, default=50, help="W&B に載せる間違えた画像の最大枚数")
    ap.add_argument("--export_tflite", action="store_true", help="学習後に tflite を書き出し、検証データで精度を確かめる")
    ap.add_argument("--out", default="train_out", help="モデルの保存先フォルダ")
    ap.add_argument("--seed", type=int, default=42)
    return ap.parse_args()


# ---------------------------------------------------------------- データ

def load_dataset(tf, path, args, shuffle):
    return tf.keras.utils.image_dataset_from_directory(
        path, image_size=(args.img_size, args.img_size), batch_size=args.batch_size,
        label_mode="int", shuffle=shuffle, seed=args.seed)


def make_augmenter(tf, kind, seed):
    if kind == "none":
        return None
    L = tf.keras.layers
    layers = [L.RandomFlip("horizontal_and_vertical", seed=seed), L.RandomRotation(0.15, seed=seed)]
    if kind == "strong":
        layers += [L.RandomZoom(0.2, seed=seed),
                   L.RandomBrightness(0.2, value_range=(0, 255), seed=seed),
                   L.RandomContrast(0.2, seed=seed)]
    return tf.keras.Sequential(layers, name=f"augment_{kind}")


# ---------------------------------------------------------------- モデル

def split_model(tf, model):
    """「backbone より前」「backbone（DenseNet121）」「backbone より後」に分ける。"""
    idx = None
    for i, layer in enumerate(model.layers):
        if isinstance(layer, tf.keras.Model):
            idx = i
    if idx is None:
        sys.exit("モデルの中に DenseNet121 などの入れ子のモデルが見つかりません。")
    return model.layers[:idx], model.layers[idx], model.layers[idx + 1:]


def set_stage(tf, model, backbone, head, finetune_layers):
    """学習させる層を決める。finetune_layers=0 なら判定の層だけ。"""
    for layer in model.layers:
        layer.trainable = False
    for layer in head:
        layer.trainable = True
    if finetune_layers > 0:
        # 入れ子のモデルは親の trainable も True にしないと、Keras のバージョンによっては中の層が学習されない
        backbone.trainable = True
        for i, layer in enumerate(backbone.layers):
            layer.trainable = i >= len(backbone.layers) - finetune_layers
            # BatchNormalization は少量データの追加学習で統計が崩れやすいので固定する
            if isinstance(layer, tf.keras.layers.BatchNormalization):
                layer.trainable = False


def count_params(weights):
    return int(sum(np.prod(w.shape) for w in weights))


class WandbEpochLogger:
    """エポックごとの値を、2段階を通した通し番号で W&B に記録する Keras コールバックを作る。"""

    def __init__(self, tf, wandb):
        self.tf, self.wandb = tf, wandb
        self.epoch = 0

    def callback(self, stage):
        outer = self

        class _CB(outer.tf.keras.callbacks.Callback):
            def on_epoch_end(self, epoch, logs=None):
                outer.epoch += 1
                data = {k: float(v) for k, v in (logs or {}).items()}
                data["epoch"] = outer.epoch
                data["stage"] = stage
                outer.wandb.log(data)

        return _CB()


# ---------------------------------------------------------------- 評価

def predict_all(model_fn, ds):
    probs, labels = [], []
    for x, y in ds:
        probs.append(model_fn(x))
        labels.append(y.numpy())
    return np.concatenate(probs), np.concatenate(labels)


def metrics_from(probs, labels, class_names):
    n = len(class_names)
    preds = probs.argmax(axis=1)
    cm = np.zeros((n, n), dtype=int)
    for t, p in zip(labels, preds):
        cm[t, p] += 1
    per_class = []
    for i, c in enumerate(class_names):
        tp = cm[i, i]
        fp = cm[:, i].sum() - tp
        fn = cm[i, :].sum() - tp
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        per_class.append((c, prec, rec, f1, int(cm[i, :].sum())))
    out = {"accuracy": float(np.trace(cm) / cm.sum()),
           "macro_f1": float(np.mean([r[3] for r in per_class]))}

    healthy = [i for i, c in enumerate(class_names) if "healthy" in c.lower()]
    if len(healthy) == 1:
        h = healthy[0]
        pred_dis = probs[:, h] < 0.5          # アプリと同じ判定
        true_dis = labels != h
        tp = int(np.sum(pred_dis & true_dis))
        fp = int(np.sum(pred_dis & ~true_dis))
        fn = int(np.sum(~pred_dis & true_dis))
        tn = int(np.sum(~pred_dis & ~true_dis))
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        out.update({"bin_accuracy": (tp + tn) / len(labels), "bin_precision": prec, "bin_recall": rec,
                    "bin_f1": 2 * prec * rec / (prec + rec) if prec + rec else 0.0,
                    "missed": fn, "false_alarm": fp})
    return out, cm, per_class, preds


def overlay(image, cam, alpha=0.45):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from PIL import Image
    h, w = image.shape[:2]
    cam_big = np.asarray(Image.fromarray(np.uint8(cam * 255)).resize((w, h), Image.BILINEAR),
                         dtype=np.float32) / 255.0
    heat = plt.get_cmap("jet")(cam_big)[..., :3] * 255.0
    return np.uint8(image * (1 - alpha) + heat * alpha)


def log_split(tf, wandb, model, split_parts, name, ds, class_names, args):
    """1つのデータ（valid / test）を評価して W&B に記録する。"""
    from gradcam import grad_cam

    probs, labels = predict_all(lambda x: model(x, training=False).numpy(), ds)
    m, cm, per_class, preds = metrics_from(probs, labels, class_names)
    print(f"[{name}] " + " / ".join(f"{k} {v:.4f}" if isinstance(v, float) else f"{k} {v}"
                                     for k, v in m.items()))

    wandb.summary.update({f"{name}/{k}": v for k, v in m.items()})
    wandb.log({
        f"{name}/confusion_matrix": wandb.plot.confusion_matrix(
            y_true=labels.tolist(), preds=preds.tolist(), class_names=class_names,
            title=f"{args.crop} {name}"),
        f"{name}/per_class": wandb.Table(columns=["class", "precision", "recall", "f1", "images"],
                                         data=[[c, p, r, f, n] for c, p, r, f, n in per_class]),
    })

    # 間違えた画像を Grad-CAM 付きで記録する（確信度が高いのに間違えたものから順に）
    paths = ds.file_paths
    wrong = [i for i in range(len(labels)) if preds[i] != labels[i]]
    wrong.sort(key=lambda i: -probs[i, preds[i]])
    if wrong and args.log_wrong > 0:
        from PIL import Image
        pre, backbone, post = split_parts
        table = wandb.Table(columns=["image", "grad_cam", "true", "pred", "p_pred", "path"])
        for i in wrong[:args.log_wrong]:
            img = np.asarray(Image.open(paths[i]).convert("RGB").resize(
                (args.img_size, args.img_size), Image.BILINEAR), dtype=np.float32)
            cam, _, _ = grad_cam(tf, pre, backbone, post, img)
            table.add_data(wandb.Image(np.uint8(img)), wandb.Image(overlay(img, cam)),
                           class_names[labels[i]], class_names[preds[i]],
                           float(probs[i, preds[i]]), paths[i])
        wandb.log({f"{name}/wrong_predictions": table})
    print(f"[{name}] 間違えた画像: {len(wrong)} 枚（W&B には最大 {args.log_wrong} 枚）")
    return m


def export_tflite(tf, model, path):
    try:
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        data = converter.convert()
    except Exception as e:  # Keras 3 などで直接変換できない場合は SavedModel を経由する
        print(f"from_keras_model での変換に失敗したため SavedModel 経由で変換します: {e}")
        saved = path + "_savedmodel"
        if hasattr(model, "export"):
            model.export(saved)
        else:
            tf.saved_model.save(model, saved)
        data = tf.lite.TFLiteConverter.from_saved_model(saved).convert()
    with open(path, "wb") as fp:
        fp.write(data)


def tflite_predict_fn(tf, path):
    interp = tf.lite.Interpreter(model_path=path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]

    def fn(x):
        res = []
        for img in x.numpy():
            interp.set_tensor(inp["index"], img[None].astype(np.float32))
            interp.invoke()
            res.append(interp.get_tensor(out["index"])[0])
        return np.array(res)

    return fn


# ---------------------------------------------------------------- 本体

def main():
    args = parse_args()

    import tensorflow as tf
    import wandb

    tf.keras.utils.set_random_seed(args.seed)
    run = wandb.init(project=args.project, name=args.run_name, config=vars(args),
                     job_type="train", tags=[args.crop, f"augment-{args.augment}"])
    # Sweep から起動された場合は、W&B が決めた値で上書きする
    for k, v in dict(wandb.config).items():
        if hasattr(args, k):
            setattr(args, k, v)

    train_ds = load_dataset(tf, args.train, args, shuffle=True)
    valid_ds = load_dataset(tf, args.valid, args, shuffle=False)
    class_names = train_ds.class_names
    if valid_ds.class_names != class_names:
        sys.exit(f"学習データと検証データのクラス名が違います: {class_names} / {valid_ds.class_names}")
    print("クラス番号とフォルダ名:", {i: c for i, c in enumerate(class_names)})
    wandb.config.update({"class_names": class_names}, allow_val_change=True)

    aug = make_augmenter(tf, args.augment, args.seed)
    if aug is not None:
        train_ds = train_ds.map(lambda x, y: (aug(x, training=True), y), num_parallel_calls=tf.data.AUTOTUNE)
    train_fit = train_ds.prefetch(tf.data.AUTOTUNE)
    valid_fit = valid_ds.prefetch(tf.data.AUTOTUNE)
    if args.steps_per_epoch:
        train_fit = train_fit.take(args.steps_per_epoch)
        valid_fit = valid_fit.take(args.steps_per_epoch)

    model = tf.keras.models.load_model(args.base_model, compile=False)
    n_out = model.output_shape[-1]
    if n_out != len(class_names):
        sys.exit(f"モデルの出力数（{n_out}）とクラス数（{len(class_names)}）が合いません。")
    pre, backbone, head = split_model(tf, model)
    print(f"backbone: {backbone.name}（{len(backbone.layers)} 層） / 判定の層: {[l.name for l in head]}")

    logger = WandbEpochLogger(tf, wandb)

    def fit(stage, epochs, lr, finetune_layers):
        if epochs <= 0:
            return
        set_stage(tf, model, backbone, head, finetune_layers)
        n_train = count_params(model.trainable_weights)
        print(f"\n===== {stage}: {epochs} エポック、学習率 {lr}、学習するパラメータ {n_train:,} 個 =====")
        wandb.log({f"trainable_params/{stage}": n_train})
        model.compile(optimizer=tf.keras.optimizers.Adam(lr),
                      loss="sparse_categorical_crossentropy", metrics=["accuracy"])
        cbs = [logger.callback(stage)]
        if args.patience > 0:
            cbs.append(tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=args.patience,
                                                        restore_best_weights=True))
        model.fit(train_fit, validation_data=valid_fit, epochs=epochs, callbacks=cbs, verbose=2)

    t0 = time.time()
    fit("head", args.epochs_head, args.lr_head, 0)
    fit("finetune", args.epochs_finetune, args.lr_finetune, args.finetune_layers)
    wandb.summary["train_minutes"] = round((time.time() - t0) / 60, 1)

    os.makedirs(args.out, exist_ok=True)
    stem = f"{args.crop.lower()}_model_{run.id}"
    h5_path = os.path.join(args.out, stem + ".h5")
    model.save(h5_path)
    print(f"\nモデルを保存しました: {h5_path}")

    split_parts = (pre, backbone, head)
    log_split(tf, wandb, model, split_parts, "valid", valid_ds, class_names, args)
    if args.test:
        test_ds = load_dataset(tf, args.test, args, shuffle=False)
        if test_ds.class_names != class_names:
            sys.exit(f"テストデータのクラス名が違います: {test_ds.class_names}")
        log_split(tf, wandb, model, split_parts, "test", test_ds, class_names, args)

    artifact = wandb.Artifact(f"{args.crop.lower()}-model", type="model",
                              metadata={"class_names": class_names, "base_model": args.base_model})
    artifact.add_file(h5_path)

    if args.export_tflite:
        tfl_path = os.path.join(args.out, stem + ".tflite")
        export_tflite(tf, model, tfl_path)
        probs, labels = predict_all(tflite_predict_fn(tf, tfl_path), valid_ds)
        m, _, _, _ = metrics_from(probs, labels, class_names)
        wandb.summary.update({f"tflite_valid/{k}": v for k, v in m.items()})
        print(f"tflite を書き出しました: {tfl_path}（検証データの正解率 {m['accuracy']:.4f}）")
        artifact.add_file(tfl_path)

    run.log_artifact(artifact)
    run.finish()


if __name__ == "__main__":
    main()
