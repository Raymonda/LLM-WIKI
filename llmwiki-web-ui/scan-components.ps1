$files = Get-ChildItem -Path "src\components" -Recurse -Filter "*.vue"
$found = $false
foreach ($f in $files) {
    $lines = Get-Content -Encoding UTF8 $f.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '[\u4e00-\u9fff]') {
            $trimmed = $line.Trim()
            if ($trimmed.StartsWith('//') -or $trimmed.StartsWith('/*') -or $trimmed.StartsWith('*') -or $trimmed.StartsWith('<!--') -or $trimmed.StartsWith('*/')) {
                continue
            }
            Write-Host ($f.Name + ":" + ($i+1) + ": " + $trimmed)
            $found = $true
        }
    }
}
if (-not $found) {
    Write-Host "No non-comment Chinese text found in components"
}
