package com.ekina.application.paper;

/**
 * Builds a short-epoch trainer that imports models from kuangliu/pytorch-cifar
 * and writes Ekina metrics.json (real CIFAR-10 accuracy — not synthetic).
 */
public final class PytorchCifarMiniTrainer {

    private PytorchCifarMiniTrainer() {}

    public static String script(String paperId, String runId, int epochs) {
        int ep = Math.max(1, Math.min(20, epochs));
        return """
                #!/usr/bin/env python3
                \"\"\"Ekina mini-goal: train ResNet18 from kuangliu/pytorch-cifar for a few epochs.\"\"\"
                import argparse, json, os, platform, time
                from datetime import datetime, timezone
                
                def main():
                    ap = argparse.ArgumentParser()
                    ap.add_argument("--out", default="metrics.json")
                    ap.add_argument("--epochs", type=int, default=%d)
                    ap.add_argument("--subset", type=int, default=8000, help="train subset size; 0=full")
                    args = ap.parse_args()
                
                    import torch
                    import torch.nn as nn
                    import torch.optim as optim
                    import torchvision
                    import torchvision.transforms as transforms
                
                    # Prefer local pytorch-cifar models if present (cloned next to this file)
                    net = None
                    model_name = "torchvision.resnet18"
                    try:
                        from models import ResNet18
                        net = ResNet18()
                        model_name = "pytorch-cifar.ResNet18"
                    except Exception as e:
                        print("EKINA_WARN models_import=" + repr(e), flush=True)
                        net = torchvision.models.resnet18(num_classes=10)
                        # CIFAR-friendly first conv
                        net.conv1 = nn.Conv2d(3, 64, kernel_size=3, stride=1, padding=1, bias=False)
                        net.maxpool = nn.Identity()
                
                    transform = transforms.Compose([
                        transforms.RandomCrop(32, padding=4),
                        transforms.RandomHorizontalFlip(),
                        transforms.ToTensor(),
                        transforms.Normalize((0.4914, 0.4822, 0.4465), (0.2023, 0.1994, 0.2010)),
                    ])
                    transform_test = transforms.Compose([
                        transforms.ToTensor(),
                        transforms.Normalize((0.4914, 0.4822, 0.4465), (0.2023, 0.1994, 0.2010)),
                    ])
                    data_root = os.environ.get("EKINA_DATA_ROOT", "/tmp/ekina_data")
                    print("EKINA_PROGRESS downloading_or_loading_cifar root=" + data_root, flush=True)
                    trainset = torchvision.datasets.CIFAR10(root=data_root, train=True, download=True, transform=transform)
                    testset = torchvision.datasets.CIFAR10(root=data_root, train=False, download=True, transform=transform_test)
                    if args.subset and args.subset > 0:
                        idx = list(range(min(args.subset, len(trainset))))
                        trainset = torch.utils.data.Subset(trainset, idx)
                        tidx = list(range(min(max(1000, args.subset // 8), len(testset))))
                        testset = torch.utils.data.Subset(testset, tidx)
                    trainloader = torch.utils.data.DataLoader(trainset, batch_size=128, shuffle=True, num_workers=2)
                    testloader = torch.utils.data.DataLoader(testset, batch_size=256, shuffle=False, num_workers=2)
                
                    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
                    net = net.to(device)
                    criterion = nn.CrossEntropyLoss()
                    optimizer = optim.SGD(net.parameters(), lr=0.1, momentum=0.9, weight_decay=5e-4)
                    print(f"EKINA_PROGRESS device={device} model={model_name} epochs={args.epochs}", flush=True)
                
                    t0 = time.time()
                    for epoch in range(args.epochs):
                        net.train()
                        total, correct, loss_sum = 0, 0, 0.0
                        for inputs, targets in trainloader:
                            inputs, targets = inputs.to(device), targets.to(device)
                            optimizer.zero_grad()
                            outputs = net(inputs)
                            loss = criterion(outputs, targets)
                            loss.backward()
                            optimizer.step()
                            loss_sum += float(loss.item()) * targets.size(0)
                            _, pred = outputs.max(1)
                            total += targets.size(0)
                            correct += pred.eq(targets).sum().item()
                        print(f"EKINA_PROGRESS epoch={epoch+1}/{args.epochs} train_acc={correct/max(total,1):.4f}", flush=True)
                
                    net.eval()
                    total, correct = 0, 0
                    with torch.no_grad():
                        for inputs, targets in testloader:
                            inputs, targets = inputs.to(device), targets.to(device)
                            outputs = net(inputs)
                            _, pred = outputs.max(1)
                            total += targets.size(0)
                            correct += pred.eq(targets).sum().item()
                    acc = correct / max(total, 1)
                    metrics = {
                        "schema": "ekina.metrics.v1",
                        "paperId": "%s",
                        "runId": "%s",
                        "domain": "CIFAR-10 ResNet18 mini-repro",
                        "status": "ok",
                        "finishedAt": datetime.now(timezone.utc).isoformat(),
                        "host": platform.node(),
                        "python": platform.python_version(),
                        "torch": torch.__version__,
                        "cuda": torch.cuda.is_available(),
                        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                        "dataset": "cifar10",
                        "model": model_name,
                        "upstream": "https://github.com/kuangliu/pytorch-cifar",
                        "results": {
                            "per_seed": [{
                                "seed": 0,
                                "val_accuracy": round(float(acc), 4),
                                "data": "cifar10",
                                "device": str(device),
                                "epochs": args.epochs,
                                "wallSec": round(time.time() - t0, 2),
                            }],
                            "mean_val_accuracy": round(float(acc), 4),
                            "baseline_note": "Mini-goal run on CIFAR-10; not a claim of matching published SOTA tables.",
                        },
                        "errors": [],
                        "wallSec": round(time.time() - t0, 2),
                    }
                    with open(args.out, "w", encoding="utf-8") as f:
                        json.dump(metrics, f, ensure_ascii=False, indent=2)
                    print("EKINA_METRICS_PATH=" + args.out, flush=True)
                    print(json.dumps({"status": "ok", "mean_val_accuracy": metrics["results"]["mean_val_accuracy"]}, ensure_ascii=False), flush=True)
                
                if __name__ == "__main__":
                    try:
                        main()
                    except Exception as e:
                        open("../FAIL", "w", encoding="utf-8").write(repr(e))
                        open("FAIL", "w", encoding="utf-8").write(repr(e))
                        raise
                """.formatted(ep, paperId, runId);
    }
}
