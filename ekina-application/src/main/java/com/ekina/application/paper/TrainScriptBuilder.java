package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a self-contained PyTorch experiment script driven by innov.json variants.
 */
@Component
public class TrainScriptBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(PaperProject project, Map<String, Object> innovPlan, String runId) throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("paperId", project.getId());
        cfg.put("runId", runId);
        cfg.put("domain", project.getDomain());
        cfg.put("variants", innovPlan.get("variants") == null ? List.of() : innovPlan.get("variants"));
        String cfgB64 = java.util.Base64.getEncoder()
                .encodeToString(mapper.writeValueAsString(cfg).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return """
                #!/usr/bin/env python3
                import argparse, base64, json, os, platform, random, time
                from datetime import datetime, timezone
                
                CFG = json.loads(base64.b64decode("%s").decode("utf-8"))
                
                def set_seed(s):
                    random.seed(s)
                    import torch
                    torch.manual_seed(s)
                    if torch.cuda.is_available():
                        torch.cuda.manual_seed_all(s)
                
                def parse_variants(variants):
                    lora_rank, lora_alpha = 8, 16
                    aug_noise = 0.0
                    use_focal = False
                    seeds = [0]
                    for v in variants or []:
                        vid = v.get("id", "")
                        params = v.get("params") or {}
                        if vid == "module_lora_rank" or params.get("lora_rank"):
                            lora_rank = int(params.get("lora_rank", lora_rank))
                            lora_alpha = int(params.get("lora_alpha", lora_alpha))
                        if vid == "dataset_aug_strong" or params.get("aug") == "strong":
                            aug_noise = 0.15
                        if vid == "loss_focal" or params.get("loss") == "focal":
                            use_focal = True
                        if vid == "ablation_seeds" and params.get("seeds"):
                            seeds = list(params.get("seeds"))
                    return dict(lora_rank=lora_rank, lora_alpha=lora_alpha, aug_noise=aug_noise,
                                use_focal=use_focal, seeds=seeds)
                
                def get_data(aug_noise=0.0, dataset="synthetic"):
                    import torch
                    from torch.utils.data import TensorDataset, DataLoader
                    # Default synthetic: avoids multi-hour CIFAR downloads on slow egress.
                    if dataset == "cifar10":
                        try:
                            import torchvision
                            from torchvision import transforms
                            tfm = [transforms.ToTensor()]
                            if aug_noise > 0:
                                tfm.append(transforms.Lambda(lambda x: torch.clamp(x + aug_noise * torch.randn_like(x), 0, 1)))
                            transform = transforms.Compose(tfm)
                            root = os.environ.get("EKINA_DATA_ROOT", "/tmp/ekina_data")
                            train = torchvision.datasets.CIFAR10(root=root, train=True, download=True, transform=transform)
                            test = torchvision.datasets.CIFAR10(root=root, train=False, download=True, transform=transforms.ToTensor())
                            train = torch.utils.data.Subset(train, list(range(0, 4000)))
                            test = torch.utils.data.Subset(test, list(range(0, 1000)))
                            return DataLoader(train, batch_size=128, shuffle=True, num_workers=0), DataLoader(test, batch_size=256, shuffle=False, num_workers=0), 10, 3*32*32, "cifar10_subset"
                        except Exception as e:
                            print("EKINA_WARN cifar_fallback=" + e.__class__.__name__, flush=True)
                    n, d, c = 4000, 64, 10
                    x = torch.randn(n, d)
                    w = torch.randn(d, c)
                    y = (x @ w).argmax(dim=1)
                    if aug_noise > 0:
                        x = x + aug_noise * torch.randn_like(x)
                    ntr = int(0.8 * n)
                    train = DataLoader(TensorDataset(x[:ntr], y[:ntr]), batch_size=128, shuffle=True)
                    test = DataLoader(TensorDataset(x[ntr:], y[ntr:]), batch_size=256)
                    return train, test, c, d, "synthetic"
                
                def train_one(seed, hp, epochs=3, dataset="synthetic"):
                    import torch
                    import torch.nn as nn
                    import torch.nn.functional as F
                
                    class LoRALinear(nn.Module):
                        def __init__(self, in_f, out_f, rank=8, alpha=16):
                            super().__init__()
                            self.base = nn.Linear(in_f, out_f)
                            self.A = nn.Parameter(torch.randn(in_f, rank) * 0.01)
                            self.B = nn.Parameter(torch.zeros(rank, out_f))
                            self.scale = alpha / max(rank, 1)
                        def forward(self, x):
                            return self.base(x) + (x @ self.A @ self.B) * self.scale
                
                    set_seed(seed)
                    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
                    print(f"EKINA_PROGRESS seed={seed} device={device} dataset={dataset}", flush=True)
                    train_loader, test_loader, nclass, in_dim, data_name = get_data(hp["aug_noise"], dataset=dataset)
                    model = nn.Sequential(
                        nn.Flatten(),
                        LoRALinear(in_dim, 128, rank=hp["lora_rank"], alpha=hp["lora_alpha"]),
                        nn.ReLU(),
                        nn.Linear(128, nclass),
                    ).to(device)
                    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
                    t0 = time.time()
                    for ep in range(epochs):
                        model.train()
                        for xb, yb in train_loader:
                            xb, yb = xb.to(device), yb.to(device)
                            logits = model(xb)
                            if hp["use_focal"]:
                                logp = F.log_softmax(logits, dim=-1)
                                p = logp.exp()
                                pt = p.gather(1, yb.view(-1, 1)).squeeze(1)
                                loss = ((1 - pt) ** 2.0 * F.nll_loss(logp, yb, reduction="none")).mean()
                            else:
                                loss = F.cross_entropy(logits, yb)
                            opt.zero_grad(); loss.backward(); opt.step()
                        print(f"EKINA_PROGRESS seed={seed} epoch={ep+1}/{epochs}", flush=True)
                    model.eval()
                    correct = total = 0
                    with torch.no_grad():
                        for xb, yb in test_loader:
                            xb, yb = xb.to(device), yb.to(device)
                            pred = model(xb).argmax(dim=-1)
                            correct += (pred == yb).sum().item()
                            total += yb.numel()
                    acc = correct / max(total, 1)
                    return {
                        "seed": seed,
                        "val_accuracy": round(float(acc), 4),
                        "data": data_name,
                        "device": str(device),
                        "epochs": epochs,
                        "wallSec": round(time.time() - t0, 2),
                        "lora_rank": hp["lora_rank"],
                        "lora_alpha": hp["lora_alpha"],
                        "aug_noise": hp["aug_noise"],
                        "focal": hp["use_focal"],
                    }
                
                def main():
                    ap = argparse.ArgumentParser()
                    ap.add_argument("--out", required=True)
                    ap.add_argument("--epochs", type=int, default=3)
                    ap.add_argument("--dataset", default=os.environ.get("EKINA_DATASET", "synthetic"),
                                    choices=["synthetic", "cifar10"])
                    args = ap.parse_args()
                    hp = parse_variants(CFG.get("variants"))
                    rows, errors = [], []
                    t0 = time.time()
                    torch_ver, cuda, gpu = None, False, None
                    try:
                        import torch
                        torch_ver = torch.__version__
                        cuda = torch.cuda.is_available()
                        gpu = torch.cuda.get_device_name(0) if cuda else None
                        for s in hp["seeds"]:
                            try:
                                rows.append(train_one(int(s), hp, epochs=args.epochs, dataset=args.dataset))
                            except Exception as e:
                                errors.append(f"seed{s}: {e}")
                    except Exception as e:
                        errors.append("torch: " + str(e))
                    mean_acc = round(sum(r["val_accuracy"] for r in rows) / len(rows), 4) if rows else None
                    metrics = {
                        "schema": "ekina.metrics.v1",
                        "paperId": CFG.get("paperId"),
                        "runId": CFG.get("runId"),
                        "domain": CFG.get("domain"),
                        "status": "ok" if rows else "failed",
                        "finishedAt": datetime.now(timezone.utc).isoformat(),
                        "host": platform.node(),
                        "python": platform.python_version(),
                        "torch": torch_ver,
                        "cuda": cuda,
                        "gpu": gpu,
                        "dataset": args.dataset,
                        "hyper": hp,
                        "variants": CFG.get("variants"),
                        "results": {
                            "per_seed": rows,
                            "mean_val_accuracy": mean_acc,
                            "baseline_note": "Incremental study numbers from this run only — not claimed SOTA.",
                        },
                        "errors": errors,
                        "wallSec": round(time.time() - t0, 2),
                    }
                    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
                    with open(args.out, "w", encoding="utf-8") as f:
                        json.dump(metrics, f, ensure_ascii=False, indent=2)
                    if not rows:
                        open("FAIL", "w", encoding="utf-8").write("\\n".join(errors) or "no rows")
                    print("EKINA_METRICS_PATH=" + args.out, flush=True)
                    print(json.dumps({"status": metrics["status"], "mean_val_accuracy": mean_acc}, ensure_ascii=False), flush=True)
                
                if __name__ == "__main__":
                    main()
                """.formatted(cfgB64);
    }
}
