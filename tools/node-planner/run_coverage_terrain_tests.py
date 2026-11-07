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
let package = Package(name: "CoverageTerrain", platforms: [.macOS(.v14)], targets: [
    .target(name: "CoreScopeViewer"),
    .testTarget(name: "CoverageTerrainTests", dependencies: ["CoreScopeViewer"])
])
''')
    sources = [
        ('CoreScopeViewer/Models/NodePlanner/CoveragePreparedStudy.swift', 'Sources/CoreScopeViewer/CoveragePreparedStudy.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageStudyRunner.swift', 'Sources/CoreScopeViewer/CoverageStudyRunner.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageStudyInputs.swift', 'Sources/CoreScopeViewer/CoverageStudyInputs.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageMapKitOverlay.swift', 'Sources/CoreScopeViewer/CoverageMapKitOverlay.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTileStore.swift', 'Sources/CoreScopeViewer/CoverageTileStore.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTilePNG.swift', 'Sources/CoreScopeViewer/CoverageTilePNG.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageRaster.swift', 'Sources/CoreScopeViewer/CoverageRaster.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageRasterTests.swift', 'Tests/CoverageTerrainTests/CoverageRasterTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageSetupValidation.swift', 'Sources/CoreScopeViewer/CoverageSetupValidation.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageSetupValidationTests.swift', 'Tests/CoverageTerrainTests/CoverageSetupValidationTests.swift'),
        ('CoreScopeViewer/Networking/CoverageTerrainCatalogLoader.swift', 'Sources/CoreScopeViewer/CoverageTerrainCatalogLoader.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainCatalogLoaderTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainCatalogLoaderTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainCatalog.swift', 'Sources/CoreScopeViewer/CoverageTerrainCatalog.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainCatalogTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainCatalogTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageStudyPublication.swift', 'Sources/CoreScopeViewer/CoverageStudyPublication.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageStudyPublicationTests.swift', 'Tests/CoverageTerrainTests/CoverageStudyPublicationTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageStudy.swift', 'Sources/CoreScopeViewer/CoverageStudy.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageArea.swift', 'Sources/CoreScopeViewer/CoverageArea.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageAreaTests.swift', 'Tests/CoverageTerrainTests/CoverageAreaTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageGridPreflight.swift', 'Sources/CoreScopeViewer/CoverageGridPreflight.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageGridPreflightTests.swift', 'Tests/CoverageTerrainTests/CoverageGridPreflightTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageGrid.swift', 'Sources/CoreScopeViewer/CoverageGrid.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageGridTests.swift', 'Tests/CoverageTerrainTests/CoverageGridTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageNodeBatch.swift', 'Sources/CoreScopeViewer/CoverageNodeBatch.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageNodeBatchTests.swift', 'Tests/CoverageTerrainTests/CoverageNodeBatchTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageNodeEvaluator.swift', 'Sources/CoreScopeViewer/CoverageNodeEvaluator.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageNodeEvaluatorTests.swift', 'Tests/CoverageTerrainTests/CoverageNodeEvaluatorTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageNodeRun.swift', 'Sources/CoreScopeViewer/CoverageNodeRun.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageNodeRunTests.swift', 'Tests/CoverageTerrainTests/CoverageNodeRunTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageNodeStatus.swift', 'Sources/CoreScopeViewer/CoverageNodeStatus.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageNodeStatusTests.swift', 'Tests/CoverageTerrainTests/CoverageNodeStatusTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageLinkBudget.swift', 'Sources/CoreScopeViewer/CoverageLinkBudget.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageLinkBudgetTests.swift', 'Tests/CoverageTerrainTests/CoverageLinkBudgetTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageRFConvergence.swift', 'Sources/CoreScopeViewer/CoverageRFConvergence.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageRFConvergenceTests.swift', 'Tests/CoverageTerrainTests/CoverageRFConvergenceTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainPath.swift', 'Sources/CoreScopeViewer/CoverageTerrainPath.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainPathTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainPathTests.swift'),
        ('CoreScopeViewer/Models/NodePlanner/CoverageTerrainProfile.swift', 'Sources/CoreScopeViewer/CoverageTerrainProfile.swift'),
        ('CoreScopeViewerTests/NodePlanner/CoverageTerrainProfileTests.swift', 'Tests/CoverageTerrainTests/CoverageTerrainProfileTests.swift'),
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
