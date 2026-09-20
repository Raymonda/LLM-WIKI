# Repairs Qoder JDT/ECJ build-output pollution.
#
# The Java language server can write malformed .class files (bare-name
# descriptors such as "LIPage;" instead of
# "Lcom/baomidou/mybatisplus/core/metadata/IPage;") into target/classes when
# its recovery compile fails to resolve a type. Maven's incremental compiler
# then skips recompiling those classes (class newer than source) and unrelated
# modules fail with "cannot access IPage / class file not found".
#
# Usage:
#   powershell -File scripts\clean-target.ps1 -Scan   # detect only, exit code = count
#   powershell -File scripts\clean-target.ps1         # detect, then delete compile output
#
# target/boot is never touched, so the script is safe while a packaged app is running.

param(
    [switch]$Scan
)

$root = Join-Path (Split-Path $PSScriptRoot -Parent) 'llmwiki'

$outputDirs = Get-ChildItem $root -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\target\\(classes|test-classes|maven-status)$' }

$malformed = @()
foreach ($dir in $outputDirs) {
    Get-ChildItem $dir.FullName -Recurse -Filter *.class -ErrorAction SilentlyContinue | ForEach-Object {
        $text = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($_.FullName))
        if ($text.Contains('LIPage;')) { $malformed += $_.FullName }
    }
}

Write-Host "Output directories: $($outputDirs.Count), malformed classes: $($malformed.Count)"
$malformed | ForEach-Object { Write-Host "  $_" }

if (-not $Scan) {
    Write-Host "Deleting compile output directories (target/boot preserved)..."
    $outputDirs | ForEach-Object { Remove-Item -Recurse -Force $_.FullName -ErrorAction SilentlyContinue }
    Write-Host "Done. Rebuild with: mvn -f $root\pom.xml test"
}

exit $malformed.Count
