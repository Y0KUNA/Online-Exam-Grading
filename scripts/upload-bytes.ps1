# upload-bytes.ps1 (TICKET 06)
# POST original fields to Gateway /storage/original endpoint
# Usage: .\upload-bytes.ps1 <uploadUrl> <objectKey> <pdfFile>

param(
    [Parameter(Mandatory=$true)]
    [string]$uploadUrl,

    [Parameter(Mandatory=$true)]
    [string]$objectKey,

    [Parameter(Mandatory=$true)]
    [string]$pdfFile
)

if (-not (Test-Path $pdfFile)) {
    Write-Error "File not found: $pdfFile"
    exit 1
}

Write-Host "Uploading $pdfFile to $uploadUrl"
Write-Host "Object key: $objectKey"

# Read file bytes
$fileBytes = [System.IO.File]::ReadAllBytes($pdfFile)
$fileSize = $fileBytes.Length

Write-Host "File size: $fileSize bytes"

# POST to MinIO via Gateway proxy
# Note: The uploadUrl includes form fields, we need to extract them
# For simplicity, we'll parse the URL and construct the form fields

try {
    $response = curl.exe -s -w "%{http_code}" -o /dev/null `
        -F "key=$objectKey" `
        -F "file=@$pdfFile" `
        $uploadUrl

    Write-Host "HTTP Status: $response"

    if ($response -eq "204") {
        Write-Host "Upload successful (204 No Content)"
        exit 0
    } elseif ($response -eq "403") {
        Write-Error "Upload failed: 403 Forbidden (invalid key or signature)"
        exit 1
    } elseif ($response -eq "405") {
        Write-Error "Upload failed: 405 Method Not Allowed (wrong path or query string)"
        exit 1
    } else {
        Write-Error "Upload failed: HTTP $response"
        exit 1
    }
} catch {
    Write-Error "Upload failed: $_"
    exit 1
}
