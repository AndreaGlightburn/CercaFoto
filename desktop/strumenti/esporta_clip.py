"""Esporta CLIP multilingue (testo) + CLIP ViT-B/32 (immagine) in ONNX quantizzato int8 per Cerca Foto Windows."""
import json, os, numpy as np, torch, torch.nn as nn
from PIL import Image
from sentence_transformers import SentenceTransformer
from transformers import CLIPModel, CLIPProcessor
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnxruntime as ort

OUT = "out"; os.makedirs(OUT, exist_ok=True)
st = SentenceTransformer("sentence-transformers/clip-ViT-B-32-multilingual-v1", device="cpu")
print(st)
tok = st.tokenizer
tok.save_vocabulary(OUT)  # vocab.txt

class Testo(nn.Module):
    def __init__(s):
        super().__init__()
        s.bert = st[0].auto_model; s.dense = st[2].linear; s.act = st[2].activation_function
    def forward(s, input_ids, attention_mask):
        h = s.bert(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
        m = attention_mask.unsqueeze(-1).to(h.dtype)
        e = (h * m).sum(1) / m.sum(1).clamp(min=1e-9)
        return s.act(s.dense(e))

t = Testo().eval()
enc = tok(["un cane sulla spiaggia"], return_tensors="pt")
torch.onnx.export(t, (enc["input_ids"], enc["attention_mask"]), f"{OUT}/clip_testo_fp32.onnx",
                  input_names=["input_ids", "attention_mask"], output_names=["emb"],
                  dynamic_axes={"input_ids": {0: "b", 1: "n"}, "attention_mask": {0: "b", 1: "n"}, "emb": {0: "b"}}, opset_version=17)

clip = CLIPModel.from_pretrained("openai/clip-vit-base-patch32", attn_implementation="eager").eval()
proc = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
class Img(nn.Module):
    def __init__(s): super().__init__(); s.m = clip
    def forward(s, pixel_values): return s.m.get_image_features(pixel_values=pixel_values)
torch.onnx.export(Img().eval(), (torch.randn(1, 3, 224, 224),), f"{OUT}/clip_immagine_fp32.onnx",
                  input_names=["pixel_values"], output_names=["emb"], dynamic_axes={"pixel_values": {0: "b"}, "emb": {0: "b"}}, opset_version=17)

# testo: anche la tabella del vocabolario (Gather), altrimenti resta 400 MB; immagine: solo MatMul (ConvInteger non c'è su CPU)
quantize_dynamic(f"{OUT}/clip_testo_fp32.onnx", f"{OUT}/clip_testo.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm", "Gather"])
quantize_dynamic(f"{OUT}/clip_immagine_fp32.onnx", f"{OUT}/clip_immagine.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])

# riferimenti per verificare l'app
testi = ["un cane sulla spiaggia", "a dog on the beach", "torta di compleanno con candeline", "bambino in bicicletta", "tramonto sul mare"]
ref_t = st.encode(testi, convert_to_numpy=True)
rng = np.random.default_rng(0)
img = Image.fromarray((rng.random((300, 400, 3)) * 255).astype(np.uint8)); img.save(f"{OUT}/prova.png")
pv = proc(images=img, return_tensors="pt")["pixel_values"]
with torch.no_grad(): ref_i = clip.get_image_features(pixel_values=pv).numpy()
so = ort.SessionOptions()
res = {"testi": testi, "ref_testo": ref_t.tolist(), "ref_immagine": ref_i.tolist(), "pixel_values_mean": float(pv.mean())}
for n in ["clip_testo", "clip_immagine"]:
    for suff in ["_fp32", ""]:
        s = ort.InferenceSession(f"{OUT}/{n}{suff}.onnx", so)
        if n == "clip_testo":
            e = tok(testi, padding=True, return_tensors="np")
            y = s.run(None, {"input_ids": e["input_ids"].astype(np.int64), "attention_mask": e["attention_mask"].astype(np.int64)})[0]
            ref = ref_t
        else:
            y = s.run(None, {"pixel_values": pv.numpy()})[0]; ref = ref_i
        cos = [float(a @ b / np.linalg.norm(a) / np.linalg.norm(b)) for a, b in zip(y, ref)]
        res[f"coseno_{n}{suff}"] = cos
        print(n, suff, cos)
res["token_esempio"] = {x: tok(x)["input_ids"] for x in testi + ["Città, perché? L'è un'àncora!"]}
json.dump(res, open(f"{OUT}/riferimenti.json", "w"), indent=1)
for f in os.listdir(OUT): print(f, os.path.getsize(f"{OUT}/{f}"))
