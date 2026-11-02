#!/usr/bin/env python3
"""Run the actual pure Swift plan model/tests on macOS without launching a simulator."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='nodescope-plan-contract-') as directory:
    work = Path(directory)
    (work/'Package.swift').write_text('''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "PlanContract", platforms: [.macOS(.v13)], targets: [
    .target(name: "CoreScopeViewer"),
    .testTarget(name: "PlanContractTests", dependencies: ["CoreScopeViewer"])
])
''')
    for source, destination in [
        ('CoreScopeViewer/Models/NodePlanner/DeploymentPlan.swift', 'Sources/CoreScopeViewer/DeploymentPlan.swift'),
        ('CoreScopeViewer/Networking/DeploymentPlanStore.swift', 'Sources/CoreScopeViewer/DeploymentPlanStore.swift'),
        ('CoreScopeViewerTests/NodePlanner/DeploymentPlanTests.swift', 'Tests/PlanContractTests/DeploymentPlanTests.swift'),
        ('CoreScopeViewerTests/NodePlanner/DeploymentPlanStoreTests.swift', 'Tests/PlanContractTests/DeploymentPlanStoreTests.swift'),
    ]:
        target = work/destination
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(root/source)
    env = os.environ | {'CLANG_MODULE_CACHE_PATH': str(work/'clang-cache'),
                        'SWIFT_MODULECACHE_PATH': str(work/'swift-cache')}
    subprocess.run(['swift', 'test', '--disable-sandbox', '--package-path', str(work)], env=env, check=True)
