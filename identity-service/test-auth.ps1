$ErrorActionPreference = 'Stop'

$baseUrl = 'http://localhost:8081'
$token = $null

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null
    )

    $headers = @{ 'Content-Type' = 'application/json' }
    if ($AccessToken) {
        $headers['Authorization'] = "Bearer $AccessToken"
    }

    $jsonBody = $null
    if ($null -ne $Body) {
        $jsonBody = ($Body | ConvertTo-Json -Depth 10)
    }

    return Invoke-RestMethod -Method $Method -Uri "$baseUrl$Path" -Headers $headers -Body $jsonBody
}

Write-Host "== 1. Đăng ký tài khoản =="
$registerBody = @{
    username = 'grader_demo_001'
    fullName = 'Demo Grader'
    email = 'grader_demo_001@example.com'
    role = 'GRADER'
    password = 'Abc12345@'
}

try {
    $registerResult = Invoke-JsonRequest -Method Post -Path '/api/v1/users' -Body $registerBody
    $registerResult | ConvertTo-Json -Depth 10
}
catch {
    Write-Host "Đăng ký lỗi hoặc chưa có quyền EXAM_OFFICE:"
    $_.Exception.Message
    Write-Host "Bạn có thể đổi username/email hoặc đảm bảo đã có tài khoản EXAM_OFFICE/Bootstrap admin."
}

Write-Host ""
Write-Host "== 2. Đăng nhập =="
$loginBody = @{
    username = 'grader_demo_001'
    password = 'Abc12345@'
}

try {
    $loginResult = Invoke-JsonRequest -Method Post -Path '/api/v1/auth/login' -Body $loginBody
    $loginResult | ConvertTo-Json -Depth 10
    $token = $loginResult.accessToken
}
catch {
    Write-Host "Đăng nhập lỗi. Kiểm tra username/password hoặc service chưa chạy."
    $_.Exception.Message
}

if ($token) {
    Write-Host ""
    Write-Host "== 3. Lấy thông tin user đang đăng nhập =="
    $meResult = Invoke-JsonRequest -Method Get -Path '/api/v1/users/me' -AccessToken $token
    $meResult | ConvertTo-Json -Depth 10

    Write-Host ""
    Write-Host "== 4. Đổi mật khẩu =="
    $changePasswordBody = @{
        currentPassword = 'Abc12345@'
        newPassword = 'NewPass123@'
    }

    try {
        Invoke-JsonRequest -Method Post -Path '/api/v1/users/me/change-password' -Body $changePasswordBody -AccessToken $token | Out-Null
        Write-Host "Đổi mật khẩu thành công."
    }
    catch {
        Write-Host "Đổi mật khẩu lỗi:"
        $_.Exception.Message
    }
}

Write-Host ""
Write-Host "Gợi ý:"
Write-Host "- Nếu 403 ở đăng ký, hãy dùng tài khoản EXAM_OFFICE hoặc bootstrap admin từ biến môi trường BOOTSTRAP_ADMIN_USERNAME/BOOTSTRAP_ADMIN_PASSWORD."
Write-Host "- Nếu 401 ở login, kiểm tra username/password hoặc xem service đã chạy trên localhost:8081 chưa."
