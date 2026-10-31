"""Builds the scene model for the app (assets/scene.onnx) from DeepLab v3+ Xception65 trained on
ADE20K (TensorFlow Models, Apache 2.0): the frozen graph is converted to ONNX and its 151-class
logits reduced to the probability of each group of classes below (sky, people, vegetation...),
on a 129 x 129 grid over a 513 x 513 input (RGB scaled to -1..1, the picture in the top-left
corner, the rest 0). Output "scene": 1 x 129 x 129 x len(GROUPS), in the order of GROUPS (the
app's Scene enum follows it).

Class numbers are ADE20K's objectInfo150 (1-based; 0 is "other"), as the model's logits.

Run by CI (the result is cached): python3 scene_model.py OUTPUT.onnx
Needs: tensorflow-cpu==2.21.0 tf2onnx==1.17.0 onnx==1.23.2 onnxruntime numpy
"""
import hashlib
import os
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

URL = "https://storage.googleapis.com/download.tensorflow.org/models/deeplabv3_xception_ade20k_train_2018_05_29.tar.gz"
SHA256 = "519adbefa0604977065b040351146ab460664435649c8c32867a34f17e8e2b9c"
GROUPS = [
    ("sky", [3]),
    ("people", [13]),
    ("vegetation", [5, 10, 18, 67, 73]),  # tree, grass, plant, flower, palm
    ("water", [22, 27, 61, 110, 114, 129]),  # water, sea, river, swimming pool, waterfall, lake
    ("mountains", [17, 35, 69]),  # mountain, rock, hill
    ("architecture", [2, 26, 43, 49, 62, 80, 85]),  # building, house, column, skyscraper, bridge, hovel, tower
    ("ground", [4, 7, 12, 14, 30, 47, 53, 55, 92, 95]),  # floor, road, sidewalk, earth, field, sand, path, runway, dirt track, land
    ("vehicles", [21, 77, 81, 84, 91, 103, 104, 117, 128]),  # car, boat, bus, truck, airplane, van, ship, minibike, bicycle
    ("animals", [127]),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(out):
    import numpy as np
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    with tempfile.TemporaryDirectory() as work:
        tarball = os.path.join(work, "model.tar.gz")
        urllib.request.urlretrieve(URL, tarball)
        if sha256(tarball) != SHA256:
            sys.exit("The downloaded DeepLab model is not the expected one")
        with tarfile.open(tarball) as t:
            t.extractall(work, filter="data")
        graph = os.path.join(work, "deeplabv3_xception_ade20k_train", "frozen_inference_graph.pb")
        raw = os.path.join(work, "raw.onnx")
        subprocess.run([sys.executable, "-m", "tf2onnx.convert", "--graphdef", graph, "--inputs", "sub_7:0",
                        "--outputs", "logits/semantic/BiasAdd:0", "--opset", "13", "--output", raw], check=True)

        m = onnx.load(raw)
        g = m.graph
        logits = g.output[0].name
        classes = sorted({c for _, cs in GROUPS for c in cs})
        # Each group is the sum of its classes' probabilities: a 0/1 matrix from classes to groups.
        sums = np.zeros((len(classes), len(GROUPS)), np.float32)
        for gi, (_, cs) in enumerate(GROUPS):
            for c in cs:
                sums[classes.index(c), gi] = 1.0
        g.initializer.append(numpy_helper.from_array(np.array(classes, np.int64), "class_index"))
        g.initializer.append(numpy_helper.from_array(sums, "group_sums"))
        g.node.extend([
            helper.make_node("Softmax", [logits], ["probs"], axis=-1),
            helper.make_node("Gather", ["probs", "class_index"], ["picked"], axis=3),
            helper.make_node("MatMul", ["picked", "group_sums"], ["scene"]),
        ])
        del g.output[:]
        g.output.append(helper.make_tensor_value_info("scene", TensorProto.FLOAT, [1, 129, 129, len(GROUPS)]))
        old = g.input[0].name
        for n in g.node:
            for i, x in enumerate(n.input):
                if x == old:
                    n.input[i] = "image"
        g.input[0].name = "image"
        onnx.checker.check_model(m)
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        part = out + ".part"
        onnx.save(m, part)

    # A plain check: blue above, green below.
    import onnxruntime as ort
    img = np.zeros((1, 513, 513, 3), np.float32)
    img[0, :256] = np.array([0.45, 0.62, 0.92]) * 2 - 1
    img[0, 256:] = np.array([0.20, 0.35, 0.15]) * 2 - 1
    out_all = ort.InferenceSession(part).run(None, {"image": img})[0][0]
    if out_all.shape != (129, 129, len(GROUPS)):
        sys.exit("The scene model gives %s" % (out_all.shape,))
    if not (out_all.sum(axis=-1).max() <= 1.001):
        sys.exit("Group probabilities add up to more than 1")
    p = out_all[..., 0]
    if not (p[:40].mean() > 0.5 and p[90:].mean() < 0.5):
        sys.exit("The scene model does not find a plain sky: %.2f above, %.2f below" % (p[:40].mean(), p[90:].mean()))
    os.replace(part, out)


if __name__ == "__main__":
    main(sys.argv[1])
