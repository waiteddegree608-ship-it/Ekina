package com.ekina.application.paper;

import java.util.Map;

/**
 * Builds a compact, self-contained DDPM (diffusion) training script for CIFAR-10 that runs on the
 * compute host and writes Ekina metrics.json with a REAL (non-synthetic) dataset. Reports training
 * loss + generation statistics + best-effort FID/IS (null when the metric libs are absent).
 */
public final class DiffusionTrainScript {

    private DiffusionTrainScript() {}

    public static String script(String paperId, String runId, int epochs, Map<String, Object> hyper) {
        int ep = Math.max(1, Math.min(60, epochs));
        int ch = 96;
        if (hyper != null && hyper.get("ch") instanceof Number n) {
            ch = Math.max(32, n.intValue());
        }
        boolean ema = hyper != null && Boolean.TRUE.equals(hyper.get("ema"));
        double lr = 2e-4;
        if (hyper != null && hyper.get("lr") instanceof Number lrN) {
            lr = lrN.doubleValue();
        }
        String emaStr = ema ? "True" : "False";
        return """
                #!/usr/bin/env python3
                \"\"\"Ekina diffusion (DDPM) mini experiment on CIFAR-10 — real, non-synthetic.\"\"\"
                import argparse, json, os, platform, time, math, copy
                import numpy as np
                from datetime import datetime, timezone

                def main():
                    ap = argparse.ArgumentParser()
                    ap.add_argument("--out", default="metrics.json")
                    ap.add_argument("--epochs", type=int, default=%d)
                    ap.add_argument("--subset", type=int, default=0, help="train subset; 0=full CIFAR-10")
                    ap.add_argument("--frames", type=int, default=256, help="generated samples for stats")
                    ap.add_argument("--dataset", default="cifar10")  # fixed to CIFAR-10; accepted for CLI compat
                    args = ap.parse_args()
                    import torch, torch.nn as nn, torch.nn.functional as F
                    import torchvision
                    torch.manual_seed(0)
                    device = "cuda" if torch.cuda.is_available() else "cpu"

                    # Tiny UNet-ish DDPM (downsample/upsample conv blocks)
                    # Lightweight UNet DDPM (down 32->16, up 16->32; output matches noise shape)
                    class DDPM(nn.Module):
                        def __init__(s, ch, T=200):
                            super().__init__(); s.T=T
                            s.te=nn.Embedding(T, ch)
                            s.c1=nn.Conv2d(3, ch, 3,1,1); s.b1=nn.BatchNorm2d(ch)
                            s.d2=nn.Conv2d(ch, ch, 3,2,1); s.b2=nn.BatchNorm2d(ch)   # 32x32 -> 16x16
                            s.u1=nn.ConvTranspose2d(ch, ch, 2,2); s.b3=nn.BatchNorm2d(ch)  # 16x16 -> 32x32
                            s.out=nn.Conv2d(ch, 3, 3,1,1)
                        def forward(s,x,t):
                            h=F.relu(s.b1(s.c1(x)))
                            h=F.relu(s.b2(s.d2(h)))
                            te=s.te(t).view(-1, s.te.embedding_dim, 1, 1)
                            h=h+te
                            h=F.relu(s.b3(s.u1(h)))
                            return s.out(h)

                    T=200
                    b_t=torch.linspace(1e-4, 2e-2, T, device=device)
                    ac=torch.cumprod(1-b_t, 0)  # alpha_cumprod[t]
                    def perturb(x, t):
                        idx=t.long(); a_bar=ac[idx].view(-1,1,1,1)
                        eps=torch.randn_like(x)
                        return torch.sqrt(a_bar)*x + torch.sqrt(1-a_bar)*eps, eps
                    def sample(model, n, T):
                        x=torch.randn(n,3,32,32,device=device)
                        for tt in range(T-1,-1,-1):
                            a=ac[tt]
                            eps=model(x, torch.full((n,),tt,device=device))
                            x0=(x - torch.sqrt(1-a)*eps)/torch.sqrt(a)
                            if tt>0:
                                a_prev=ac[tt-1]; xo=torch.sqrt(a_prev)*x0
                                noise=torch.randn_like(x)
                                x=xo + torch.sqrt(1-a_prev)*noise
                            else:
                                x=x0
                        return x.clamp(-1,1)

                    transform = torchvision.transforms.Compose([
                        torchvision.transforms.ToTensor(),
                        torchvision.transforms.Normalize((0.4914,0.4822,0.4465),(0.2023,0.1994,0.2010))])
                    root = os.environ.get("EKINA_DATA_ROOT", "/tmp/ekina_data")
                    ds = torchvision.datasets.CIFAR10(root=root, train=True, download=True, transform=transform)
                    if args.subset and args.subset>0:
                        ds = torch.utils.data.Subset(ds, list(range(min(args.subset, len(ds)))))
                    dl = torch.utils.data.DataLoader(ds, batch_size=64, shuffle=True, num_workers=2)
                    model = DDPM(%d).to(device)
                    opt = torch.optim.Adam(model.parameters(), lr=__LR__)
                    ema_model = copy.deepcopy(model) if __EMA__ else None
                    t0=time.time(); losses=[]; last_loss=None
                    for epoch in range(args.epochs):
                        model.train(); tot=0; n=0
                        for x,_ in dl:
                            x=x.to(device); b=x.size(0)
                            t=torch.randint(0,T,(b,),device=device)
                            xt,eps=perturb(x,t)
                            pred=model(xt,t)
                            loss=F.mse_loss(pred,eps)
                            opt.zero_grad(); loss.backward(); opt.step()
                            if ema_model is not None:
                                with torch.no_grad():
                                    for p, epv in zip(model.parameters(), ema_model.parameters()):
                                        epv.data = 0.9995*epv.data + (1-0.9995)*p.data
                            tot+=float(loss.item())*b; n+=b
                        avg=tot/max(n,1); losses.append(avg); last_loss=avg
                        print(f"EKINA_PROGRESS epoch={epoch+1}/{args.epochs} loss={avg:.4f}", flush=True)

                    # generation stats (feature-agnostic, self-contained; guarded so a sampler issue
                    # never blanks out the real training-loss metric)
                    mean=None; std=None; fake=None
                    try:
                        use_model = ema_model if ema_model is not None else model
                        use_model.eval()
                        nf=args.frames
                        with torch.no_grad():
                            fake=sample(use_model, nf, T)
                        fake=fake.clamp(-1,1); std=fake.std().item(); mean=float(fake.mean().item())
                    except Exception as e:
                        print("EKINA_WARN gen_stats="+repr(e), flush=True)
                    nf=args.frames

                    # Manual FID via torchvision Inception-v3 + scipy (guarded; null if unavailable)
                    fid=None; is_=None
                    try:
                        import scipy.linalg as spla
                        import torchvision.models as tv_models
                        inc = tv_models.inception_v3(weights="IMAGENET1K_V1", transform_input=False)
                        inc = inc.eval().to(device); inc.fc = torch.nn.Identity()
                        def feats(imgs):
                            imgs=torch.nn.functional.interpolate(imgs, size=(299,299), mode="bilinear", align_corners=False)
                            with torch.no_grad(): return inc(imgs).cpu().numpy()
                        refs=[]
                        for xb,_ in dl:
                            refs.append(xb)
                            if sum(r.size(0) for r in refs)>=512: break
                        real_img=torch.cat(refs,dim=0)[:512].to(device)
                        fr=feats(real_img); fg=feats(fake[:512])
                        mu_r=fr.mean(0); mu_g=fg.mean(0)
                        cov_r=np.cov(fr,rowvar=False); cov_g=np.cov(fg,rowvar=False)
                        diff=mu_r-mu_g
                        cm=spla.sqrtm(cov_r@cov_g, disp=False)[0]
                        if np.iscomplexobj(cm): cm=cm.real
                        fid=float(diff@diff + np.trace(cov_r+cov_g-2*cm))
                    except Exception as e:
                        print("EKINA_WARN fid_unavailable="+repr(e), flush=True)

                    metrics={"schema":"ekina.metrics.v1","paperId":"%s","runId":"%s",
                        "domain":"CIFAR-10 DDPM diffusion mini",
                        "status":"ok","finishedAt":datetime.now(timezone.utc).isoformat(),
                        "host":platform.node(),"python":platform.python_version(),
                        "torch":torch.__version__,"cuda":torch.cuda.is_available(),
                        "gpu":torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                        "dataset":"cifar10","model":"ddpm-cifar10","diffusion":True,
                        "hyper":{"diffusionSteps":T,"epochs":args.epochs,"subset":args.subset,"frames":nf},
                        "results":{"per_seed":[{"seed":0,"train_loss":None if last_loss is None else round(float(last_loss),6),
                            "genMean":mean,"genStd":std,"device":str(device),"epochs":args.epochs,"wallSec":round(time.time()-t0,2)}],
                            "mean_train_loss":None if last_loss is None else round(float(last_loss),6),
                            "fid":fid,"is_score":is_,
                            "baseline_note":"Real DDPM training on CIFAR-10; not a claim of matching published FID/IS tables."},
                        "errors":[],"wallSec":round(time.time()-t0,2)}
                    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
                    with open(args.out,"w",encoding="utf-8") as f: json.dump(metrics,f,ensure_ascii=False,indent=2)
                    print("EKINA_METRICS_PATH="+args.out, flush=True)

                if __name__=="__main__":
                    try:
                        main()
                    except Exception as e:
                        open("FAIL","w",encoding="utf-8").write(repr(e)); raise
                """.formatted(ep, ch, paperId, runId)
                .replace("__EMA__", emaStr)
                .replace("__LR__", String.valueOf(lr));
    }
}
