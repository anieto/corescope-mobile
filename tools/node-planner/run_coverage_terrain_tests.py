#!/usr/bin/env python3
"""Test the app's native terrain reader/mosaic on macOS without a simulator or network."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='nodescope-coverage-terrain-') as directory:
    work = Path(directory)
    (work/'Package.swift').write_text('''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "CoverageTerrain", platforms: [.macOS(.v13)], targets: [
    .target(name: "CoreScopeViewer"),
    .testTarget(name: "CoverageTerrainTests", dependencies: ["CoreScopeViewer"])
])
''')
    sources = [
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainFootprint.swift', 'Sources/CoreScopeViewer/CoverageTerrainFootprint.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainFootprintTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainFootprintTests.swift'),
        ('CoreScopeViewer/Networking/CoverageTerrainDownloader.swift', 'Sources/CoreScopeViewer/CoverageTerrainDownloader.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainDownloaderTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainDownloaderTests.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainDecoderTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainDecoderTests.swift'),
        ('CoreScopeViewer/Networking/CoverageTerrainDecoder.swift', 'Sources/CoreScopeViewer/CoverageTerrainDecoder.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainDiskCacheTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainDiskCacheTests.swift'),
        ('CoreScopeViewer/Networking/CoverageTerrainDiskCache.swift', 'Sources/CoreScopeViewer/CoverageTerrainDiskCache.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainDescriptor.swift', 'Sources/CoreScopeViewer/CoverageTerrainDescriptor.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainDescriptorTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainDescriptorTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/LeasedTerrainReader.swift', 'Sources/CoreScopeViewer/LeasedTerrainReader.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainMosaic.swift', 'Sources/CoreScopeViewer/CoverageTerrainMosaic.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainMosaicTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainMosaicTests.swift'),
    ]
    for source, destination in sources:
        target = work/destination
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(root/source)
    env = os.environ | {'CLANG_MODULE_CACHE_PATH': str(work/'clang-cache'), 'SWIFT_MODULECACHE_PATH': str(work/'swift-cache')}
    subprocess.run(['swift', 'test', '--disable-sandbox', '--package-path', str(work)], env=env, check=True)
