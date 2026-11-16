# smoke-upload.ps1 (TICKET 06)
# End-to-end upload test: init -> upload bytes -> complete
# Usage: .\smoke-upload.ps1 <gatewayUrl> <examId> <candidateId> <templateId> <pdfFile>

param(
    [Parameter(Mandatory=$false)]
    [string]$gatewayUrl = "http://localhost:8080",

    [Parameter(Mandatory=$false)]
    [string]$examId = "00000000-0000-0000-0000-000000000001",

    [Parameter(Mandatory=$false)]
    [string]$candidateId = "00000000-0000-0000-0000-000000000001",

    [Parameter(Mandatory=$false)]
    [string]$templateId = "00000000-0000-0000-0000-000000000001",

    [Parameter(Mandatory=$true)]
    [string]$pdfFile
)

if (-not (Test-Path $pdfFile)) {
    Write-Error "File not found: $pdfFile"
    exit 1
}

Write-Host "=== Upload End-to-End Test ==="
Write-Host "Gateway: $gatewayUrl"
Write-Host "Exam ID: $examId"
Write-Host "Candidate ID: $candidateId"
Write-Host "Template ID: $templateId"
Write-Host "PDF File: $pdfFile"

# Step 1: Init submission
Write-Host "`n[Step 1] Init submission..."
$initUrl = "$gatewayUrl/api/v1/exams/$examId/submissions"
$fileSize = (Get-Item $pdfFile).Length

$initBody = @{
    candidateId = $candidateId
    templateId = $templateId
    fileSizeBytes = $fileSize
} | ConvertTo-Json

try {
    $initResponse = Invoke-RestMethod -Uri $initUrl -Method POST -Body $initBody -ContentType "application/json"
    Write-Host "Init successful!"
    Write-Host "  Submission ID: $($initResponse.submissionId)"
    Write-Host "  Upload URL: $($initResponse.upload.url)"
    Write-Host "  Upload Fields: $($initResponse.upload.fields | ConvertTo-Json)"

    $submissionId = $initResponse.submissionId
    $uploadUrl = $initResponse.upload.url
    $objectKey = $initResponse.upload.fields.key
} catch {
    Write-Error "Init failed: $_"
    Write-Error "Status: $($_.Exception.Response.StatusCode.value__)"
    exit 1
}

# Step 2: Upload bytes
Write-Host "`n[Step 2] Upload PDF bytes..."
& .\upload-bytes.ps1 $uploadUrl $objectKey $pdfFile
if ($LASTEXITCODE -ne 0) {
    Write-Error "Upload failed"
    exit 1
}

# Step 3: Complete submission
Write-Host "`n[Step 3] Complete submission..."
$completeUrl = "$gatewayUrl/api/v1/submissions/$submissionId/complete"

try {
    $completeResponse = Invoke-RestMethod -Uri $completeUrl -Method POST -ContentType "application/json"
    Write-Host "Complete successful!"
    Write-Host "  Status: $($completeResponse.status)"
    Write-Host "  Progress: $($completeResponse.progress)"
} catch {
    Write-Error "Complete failed: $_"
    Write-Error "Status: $($_.Exception.Response.StatusCode.value__)"
    exit 1
}

# Step 4: Verify status
Write-Host "`n[Step 4] Verify submission status..."
$detailUrl = "$gatewayUrl/api/v1/submissions/$submissionId"

try {
    $detailResponse = Invoke-RestMethod -Uri $detailUrl -Method Get
    Write-Host "Submission status: $($detailResponse.status)"
    Write-Host "Progress: $($detailResponse.progress)"

    if ($detailResponse.status -eq "UPLOADED") {
        Write-Host "`n=== Test PASSED ==="
        exit 0
    } else {
        Write-Error "Expected status UPLOADED, got $($detailResponse.status)"
        exit 1
    }
} catch {
    Write-Error "Verify failed: $_"
    exit 1
}
