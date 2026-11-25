param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Serial = ""
)

# vivo 的"继续安装"确认框会把 adb install 卡住，这里一边装一边点掉它。
# 关键：必须显式指定设备 —— 无线调试会自动连回来，双设备时 adb install 的报错是空的。
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root "_install_out.txt"
$err = Join-Path $root "_install_err.txt"

if (-not $Serial) {
    & $adb start-server *> $null
    for ($i = 0; $i -lt 30; $i++) {
        $line = (& $adb devices 2>$null | Select-Object -Skip 1 |
            Where-Object { $_ -match "\sdevice\s*$" } | Select-Object -First 1)
        if ($line) { $Serial = ($line -split "\s+")[0]; break }
        Start-Sleep -Milliseconds 800
    }
}
if (-not $Serial) {
    Write-Output "!! no device (usb or wireless debugging)"
    & $adb devices -l
    exit 1
}
Write-Output "== device $Serial =="

$proc = Start-Process -FilePath $adb -NoNewWindow -PassThru `
    -ArgumentList @("-s", $Serial, "install", "-r", "-t", $Apk) `
    -RedirectStandardOutput $out -RedirectStandardError $err

$taps = 0
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 700
    if ($proc.HasExited) { break }
    # 确认按钮的位置在 vivo 上很稳定；没有弹窗时点一下也无副作用
    & $adb -s $Serial shell input tap 540 2120 *> $null
    $taps++
}

Write-Output "== output =="
Get-Content $out -ErrorAction SilentlyContinue
Get-Content $err -ErrorAction SilentlyContinue
Write-Output "== taps $taps =="
